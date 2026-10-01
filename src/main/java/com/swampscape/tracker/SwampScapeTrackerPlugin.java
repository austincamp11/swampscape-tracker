package com.swampscape.tracker;

import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.hiscore.HiscoreSkillType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "SwampScape Tracker",
	description = "Opt-in clan tracking for game time, kills, deaths, and valuable loot",
	tags = {"clan", "tracker", "loot", "leaderboard"}
)
public class SwampScapeTrackerPlugin extends Plugin
{
	static final String PLUGIN_VERSION = "0.4.0";
	private static final int TICKS_PER_UPLOAD = 100;
	private static final int DEATH_SETTLE_TICKS = 8;
	private static final int DEATH_SCREENSHOT_TIMEOUT_TICKS = 20;
	private static final int SCREENSHOT_MAX_WIDTH = 2560;
	private static final int SCREENSHOT_MAX_HEIGHT = 1440;
	private static final int SCREENSHOT_FALLBACK_WIDTH = 1920;
	private static final int SCREENSHOT_FALLBACK_HEIGHT = 1080;
	private static final int SCREENSHOT_MAX_BYTES = 7 * 1024 * 1024;
	private static final long MAX_DEATH_LOSS_VALUE = 50_000_000_000L;
	private static final long DUPLICATE_WINDOW_MILLIS = 2_500L;
	private static final long DUPLICATE_KILL_WINDOW_MILLIS = 8_000L;
	private static final Pattern KILL_COUNT_PATTERN = Pattern.compile(
		"^Your (?:(?:completion count for |subdued |completed ))?(.+?) " +
		"(?:(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?([0-9,]+)\\.?$",
		Pattern.CASE_INSENSITIVE);
	private static final Set<String> RAID_NAMES = buildRaidNames();
	private static final Map<String, String> BOSS_NAMES = buildBossNames();

	@Inject
	private Client client;

	@Inject
	private SwampScapeTrackerConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private TrackerClient trackerClient;

	@Inject
	private ItemManager itemManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	private final Map<String, Long> recentLoot = new HashMap<>();
	private final Map<String, Long> recentKills = new HashMap<>();
	private int loggedInTicks;
	private String lastOpponentName;
	private int lastOpponentTick = Integer.MIN_VALUE;
	private PendingDeath pendingDeath;

	@Provides
	SwampScapeTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SwampScapeTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (SwampScapeTrackerConfig.LEGACY_SERVER_URL.equalsIgnoreCase(config.serverUrl().trim()))
		{
			configManager.setConfiguration(
				"swampscapeTracker",
				"serverUrl",
				SwampScapeTrackerConfig.DEFAULT_SERVER_URL);
		}
		loggedInTicks = 0;
		recentLoot.clear();
		recentKills.clear();
		lastOpponentName = null;
		lastOpponentTick = Integer.MIN_VALUE;
		pendingDeath = null;
	}

	@Override
	protected void shutDown()
	{
		finalizePendingDeath();
		flushElapsedTime();
		flush();
		recentLoot.clear();
		recentKills.clear();
		pendingDeath = null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!config.shareActivity() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			rememberOpponent(localPlayer.getInteracting());
			for (Actor npc : client.getTopLevelWorldView().npcs())
			{
				if (npc.getInteracting() == localPlayer)
				{
					rememberOpponent(npc);
				}
			}
			// Prefer a player over an NPC when both are attacking on the same PvP tick.
			for (Player player : client.getTopLevelWorldView().players())
			{
				if (player != localPlayer && player.getInteracting() == localPlayer)
				{
					rememberOpponent(player);
				}
			}
		}
		if (pendingDeath != null)
		{
			pendingDeath.ticksAfterDeath++;
			if (pendingDeath.ticksAfterDeath >= DEATH_SETTLE_TICKS &&
				(pendingDeath.screenshotComplete || pendingDeath.ticksAfterDeath >= DEATH_SCREENSHOT_TIMEOUT_TICKS))
			{
				finalizePendingDeath();
			}
		}

		if (config.trackTime())
		{
			loggedInTicks++;
			if (loggedInTicks >= TICKS_PER_UPLOAD)
			{
				trackerClient.enqueue(TrackerEvent.gameTime(60));
				loggedInTicks -= TICKS_PER_UPLOAD;
			}
		}

		if (client.getTickCount() % TICKS_PER_UPLOAD == 0)
		{
			flush();
			pruneDuplicateCache();
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!config.shareActivity() || !config.trackDeaths())
		{
			return;
		}
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}
		if (event.getTarget() == localPlayer && event.getSource() != localPlayer)
		{
			rememberOpponent(event.getSource());
		}
		else if (event.getSource() == localPlayer && event.getTarget() != localPlayer)
		{
			rememberOpponent(event.getTarget());
		}
	}

	private void rememberOpponent(Actor actor)
	{
		if (actor == null || actor == client.getLocalPlayer())
		{
			return;
		}
		String name = safeName(actor.getName(), null);
		if (name != null)
		{
			lastOpponentName = Text.removeTags(name);
			lastOpponentTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN && loggedInTicks > 0)
		{
			flushElapsedTime();
			flush();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (config.shareActivity() && config.trackDeaths() && event.getActor() == client.getLocalPlayer())
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				return;
			}
			Actor interacting = localPlayer.getInteracting();
			String killedBy = interacting == null
				? null
				: safeName(Text.removeTags(safeName(interacting.getName(), "")), null);
			if (killedBy == null && lastOpponentName != null && client.getTickCount() - lastOpponentTick <= 50)
			{
				killedBy = lastOpponentName;
			}
			PendingDeath death = new PendingDeath(
				safeName(localPlayer.getName(), "Unknown player"),
				safeName(killedBy, "Unknown or environmental cause"),
				captureCarriedItems());
			pendingDeath = death;
			if (config.deathScreenshots())
			{
				captureDeathScreenshot(death);
			}
			else
			{
				death.screenshotComplete = true;
			}
		}
	}

	private void captureDeathScreenshot(PendingDeath death)
	{
		drawManager.requestNextFrameListener((Image image) -> executor.submit(() ->
		{
			try
			{
				death.screenshotBase64 = encodeScreenshot(image);
			}
			catch (IOException error)
			{
				log.debug("Unable to encode SwampScape death screenshot", error);
			}
			finally
			{
				death.screenshotComplete = true;
			}
		}));
	}

	private void finalizePendingDeath()
	{
		PendingDeath death = pendingDeath;
		if (death == null)
		{
			return;
		}
		pendingDeath = null;
		long lostValue = calculateLostValue(death.itemsBeforeDeath, captureCarriedItems());
		trackerClient.enqueue(TrackerEvent.death(lostValue, death.killedBy, death.screenshotBase64));
		trackerClient.flush(death.rsn, PLUGIN_VERSION);
	}

	private Map<Integer, CarriedItem> captureCarriedItems()
	{
		Map<Integer, CarriedItem> result = new HashMap<>();
		captureContainer(result, client.getItemContainer(InventoryID.INV));
		captureContainer(result, client.getItemContainer(InventoryID.WORN));
		return result;
	}

	private void captureContainer(Map<Integer, CarriedItem> result, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() < 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			CarriedItem existing = result.get(item.getId());
			long quantity = item.getQuantity() + (existing == null ? 0L : existing.quantity);
			long unitPrice = existing == null ? Math.max(0L, itemManager.getItemPrice(item.getId())) : existing.unitPrice;
			result.put(item.getId(), new CarriedItem(quantity, unitPrice));
		}
	}

	private static long calculateLostValue(Map<Integer, CarriedItem> before, Map<Integer, CarriedItem> after)
	{
		long total = 0L;
		for (Map.Entry<Integer, CarriedItem> entry : before.entrySet())
		{
			CarriedItem remaining = after.get(entry.getKey());
			long lostQuantity = Math.max(0L, entry.getValue().quantity - (remaining == null ? 0L : remaining.quantity));
			long itemValue = Math.min(MAX_DEATH_LOSS_VALUE, lostQuantity * (long) entry.getValue().unitPrice);
			total = Math.min(MAX_DEATH_LOSS_VALUE, total + itemValue);
		}
		return total;
	}

	private static String encodeScreenshot(Image image) throws IOException
	{
		BufferedImage source = ImageUtil.bufferedImageFromImage(image);
		BufferedImage output = resizeScreenshot(source, SCREENSHOT_MAX_WIDTH, SCREENSHOT_MAX_HEIGHT);
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		if (!ImageIO.write(output, "png", png))
		{
			throw new IOException("No PNG encoder is available");
		}
		byte[] encoded = png.toByteArray();
		if (encoded.length > SCREENSHOT_MAX_BYTES)
		{
			output = resizeScreenshot(source, SCREENSHOT_FALLBACK_WIDTH, SCREENSHOT_FALLBACK_HEIGHT);
			encoded = encodeJpeg(output, 0.94f);
		}
		return Base64.getEncoder().encodeToString(encoded);
	}

	private static BufferedImage resizeScreenshot(BufferedImage source, int maximumWidth, int maximumHeight)
	{
		double scale = Math.min(1.0, Math.min(
			maximumWidth / (double) source.getWidth(),
			maximumHeight / (double) source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
		BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = output.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		graphics.drawImage(source, 0, 0, width, height, null);
		graphics.dispose();
		return output;
	}

	private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext())
		{
			throw new IOException("No JPEG encoder is available");
		}
		ImageWriter writer = writers.next();
		ImageWriteParam parameters = writer.getDefaultWriteParam();
		parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		parameters.setCompressionQuality(quality);
		try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes))
		{
			writer.setOutput(output);
			writer.write(null, new IIOImage(image, null, null), parameters);
		}
		finally
		{
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static final class CarriedItem
	{
		private final long quantity;
		private final long unitPrice;

		private CarriedItem(long quantity, long unitPrice)
		{
			this.quantity = quantity;
			this.unitPrice = unitPrice;
		}
	}

	private static final class PendingDeath
	{
		private final String rsn;
		private final String killedBy;
		private final Map<Integer, CarriedItem> itemsBeforeDeath;
		private int ticksAfterDeath;
		private volatile boolean screenshotComplete;
		private volatile String screenshotBase64;

		private PendingDeath(String rsn, String killedBy, Map<Integer, CarriedItem> itemsBeforeDeath)
		{
			this.rsn = rsn;
			this.killedBy = killedBy;
			this.itemsBeforeDeath = itemsBeforeDeath;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.shareActivity() || !config.trackKills() ||
			(event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM))
		{
			return;
		}
		Matcher matcher = KILL_COUNT_PATTERN.matcher(Text.removeTags(event.getMessage()).trim());
		if (!matcher.matches())
		{
			return;
		}
		recordActivityKill(matcher.group(1));
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.shareActivity())
		{
			return;
		}
		String source = safeName(event.getNpc().getName(), "Unknown NPC");
		String bossName = BOSS_NAMES.get(normalizeActivityName(source));
		if (config.trackKills() && bossName != null)
		{
			recordKill("BOSS", bossName);
		}
		recordLoot(event.getItems(), source);
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		if (!config.shareActivity())
		{
			return;
		}
		String source = safeName(event.getPlayer().getName(), "Player");
		if (config.trackKills())
		{
			recordKill("PVP", source);
		}
		recordLoot(event.getItems(), source);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (config.shareActivity())
		{
			String source = safeName(event.getName(), "Loot event");
			if (config.trackKills())
			{
				recordActivityKill(source);
			}
			recordLoot(event.getItems(), source);
		}
	}

	private void recordActivityKill(String activityName)
	{
		String normalized = normalizeActivityName(activityName);
		if (RAID_NAMES.contains(normalized))
		{
			recordKill("RAID", canonicalRaidName(normalized, activityName));
			return;
		}
		String bossName = BOSS_NAMES.get(normalized);
		if (bossName != null)
		{
			recordKill("BOSS", bossName);
		}
	}

	private void recordKill(String killType, String activityName)
	{
		long now = System.currentTimeMillis();
		String key = killType + ":" + normalizeActivityName(activityName);
		Long previous = recentKills.put(key, now);
		if (previous == null || now - previous >= DUPLICATE_KILL_WINDOW_MILLIS)
		{
			trackerClient.enqueue(TrackerEvent.kill(activityName, killType));
		}
	}

	private void recordLoot(Collection<ItemStack> items, String source)
	{
		if (!config.trackLoot())
		{
			return;
		}
		long totalValue = 0L;
		long totalQuantity = 0L;
		long featuredValue = -1L;
		int featuredItemId = 0;
		List<String> itemNames = new ArrayList<>();
		StringBuilder signatureBuilder = new StringBuilder(normalizeActivityName(source));
		for (ItemStack stack : items)
		{
			if (stack.getId() < 0 || stack.getQuantity() <= 0)
			{
				continue;
			}
			long unitPrice = Math.max(0L, itemManager.getItemPrice(stack.getId()));
			long stackValue = unitPrice * stack.getQuantity();
			totalValue = Math.min(Integer.MAX_VALUE, totalValue + stackValue);
			totalQuantity = Math.min(Integer.MAX_VALUE, totalQuantity + stack.getQuantity());
			if (stackValue > featuredValue)
			{
				featuredValue = stackValue;
				featuredItemId = stack.getId();
			}
			ItemComposition item = itemManager.getItemComposition(stack.getId());
			String itemName = safeName(item.getName(), "Unknown item");
			itemNames.add(itemName + (stack.getQuantity() > 1 ? " x" + stack.getQuantity() : ""));
			signatureBuilder.append(':').append(stack.getId()).append('x').append(stack.getQuantity());
		}
		if (totalValue < config.lootThreshold() || itemNames.isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		String signature = signatureBuilder.toString();
		Long previous = recentLoot.put(signature, now);
		if (previous != null && now - previous < DUPLICATE_WINDOW_MILLIS)
		{
			return;
		}

		String itemSummary = itemNames.size() == 1
			? itemNames.get(0)
			: String.join(", ", itemNames.subList(0, Math.min(3, itemNames.size()))) +
				(itemNames.size() > 3 ? " +" + (itemNames.size() - 3) + " more" : "");
		if (itemSummary.length() > 120)
		{
			itemSummary = itemSummary.substring(0, 117) + "...";
		}
		enqueueLoot(
			(int) totalValue,
			(int) totalQuantity,
			featuredItemId,
			itemSummary,
			source);
	}

	private void enqueueLoot(int value, int quantity, int itemId, String itemName, String source)
	{
		Player localPlayer = client.getLocalPlayer();
		String rsn = localPlayer == null ? null : localPlayer.getName();
		if (!config.lootScreenshots())
		{
			trackerClient.enqueue(TrackerEvent.loot(value, quantity, itemId, itemName, source, null));
			if (rsn != null)
			{
				trackerClient.flush(rsn, PLUGIN_VERSION);
			}
			return;
		}
		drawManager.requestNextFrameListener((Image image) -> executor.submit(() ->
		{
			String screenshotBase64 = null;
			try
			{
				screenshotBase64 = encodeScreenshot(image);
			}
			catch (IOException error)
			{
				log.debug("Unable to encode SwampScape loot screenshot", error);
			}
			trackerClient.enqueue(TrackerEvent.loot(value, quantity, itemId, itemName, source, screenshotBase64));
			if (rsn != null)
			{
				trackerClient.flush(rsn, PLUGIN_VERSION);
			}
		}));
	}

	private void flushElapsedTime()
	{
		if (config.shareActivity() && config.trackTime() && loggedInTicks > 0)
		{
			int elapsedSeconds = Math.max(1, Math.round(loggedInTicks * 0.6f));
			trackerClient.enqueue(TrackerEvent.gameTime(elapsedSeconds));
		}
		loggedInTicks = 0;
	}

	private void flush()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			trackerClient.flush(localPlayer.getName(), PLUGIN_VERSION);
		}
	}

	private void pruneDuplicateCache()
	{
		long now = System.currentTimeMillis();
		long cutoff = now - DUPLICATE_WINDOW_MILLIS;
		Iterator<Map.Entry<String, Long>> iterator = recentLoot.entrySet().iterator();
		while (iterator.hasNext())
		{
			if (iterator.next().getValue() < cutoff)
			{
				iterator.remove();
			}
		}
		long killCutoff = now - DUPLICATE_KILL_WINDOW_MILLIS;
		Iterator<Map.Entry<String, Long>> killIterator = recentKills.entrySet().iterator();
		while (killIterator.hasNext())
		{
			if (killIterator.next().getValue() < killCutoff)
			{
				killIterator.remove();
			}
		}
	}

	private static Set<String> buildRaidNames()
	{
		Set<String> names = new HashSet<>();
		String[] raids = {
			"Chambers of Xeric",
			"Chambers of Xeric: Challenge Mode",
			"Theatre of Blood",
			"Theatre of Blood: Hard Mode",
			"Theatre of Blood: Entry Mode",
			"Theatre of Blood: Story Mode",
			"Tombs of Amascut",
			"Tombs of Amascut: Expert Mode",
			"Tombs of Amascut: Entry Mode"
		};
		for (String raid : raids)
		{
			names.add(normalizeActivityName(raid));
		}
		return Collections.unmodifiableSet(names);
	}

	private static Map<String, String> buildBossNames()
	{
		Map<String, String> names = new HashMap<>();
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (skill.getType() == HiscoreSkillType.BOSS && !RAID_NAMES.contains(normalizeActivityName(skill.getName())))
			{
				names.put(normalizeActivityName(skill.getName()), skill.getName());
			}
		}
		names.put(normalizeActivityName("Dawn"), "Grotesque Guardians");
		names.put(normalizeActivityName("Dusk"), "Grotesque Guardians");
		names.put(normalizeActivityName("The Nightmare"), "Nightmare");
		return Collections.unmodifiableMap(names);
	}

	private static String canonicalRaidName(String normalized, String fallback)
	{
		for (HiscoreSkill skill : HiscoreSkill.values())
		{
			if (normalizeActivityName(skill.getName()).equals(normalized))
			{
				return skill.getName();
			}
		}
		return safeName(Text.removeTags(fallback), "Raid");
	}

	private static String normalizeActivityName(String value)
	{
		return Text.removeTags(safeName(value, ""))
			.toLowerCase(Locale.ENGLISH)
			.replace(":", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String safeName(String value, String fallback)
	{
		return value == null || value.trim().isEmpty() ? fallback : value;
	}
}
