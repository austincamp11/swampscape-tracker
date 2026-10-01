package com.swampscape.tracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("swampscapeTracker")
public interface SwampScapeTrackerConfig extends Config
{
	String DEFAULT_SERVER_URL = "https://ss-n1.tail4f3ba9.ts.net:8443";
	String LEGACY_SERVER_URL = "https://campwk001.tail4f3ba9.ts.net:8443";

	@ConfigItem(
		keyName = "shareActivity",
		name = "Share clan activity",
		description = "Send the tracked activity listed below to your clan's SwampScape service.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0
	)
	default boolean shareActivity()
	{
		return false;
	}

	@ConfigItem(
		keyName = "serverUrl",
		name = "Server URL",
		description = "SwampScape's secure tracker endpoint. Change this only when instructed.",
		position = 1
	)
	default String serverUrl()
	{
		return DEFAULT_SERVER_URL;
	}

	@ConfigItem(
		keyName = "connectionToken",
		name = "Connection token",
		description = "The private token supplied by /tracker connect. Never share it.",
		secret = true,
		position = 2
	)
	default String connectionToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "trackTime",
		name = "Track in-game time",
		description = "Count time only while fully logged in.",
		position = 3
	)
	default boolean trackTime()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackKills",
		name = "Track boss, raid & PvP",
		description = "Count recognized boss kills, raid completions, and PvP kills. Ordinary NPCs are excluded.",
		position = 4
	)
	default boolean trackKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackDeaths",
		name = "Track deaths",
		description = "Count deaths of your logged-in player.",
		position = 5
	)
	default boolean trackDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathScreenshots",
		name = "Share death screenshots",
		description = "Attach a compressed game-window screenshot to death posts. Visible game chat may be included.",
		position = 6
	)
	default boolean deathScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackLoot",
		name = "Track valuable loot",
		description = "Send individual loot stacks at or above the configured GE value.",
		position = 7
	)
	default boolean trackLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lootScreenshots",
		name = "Share loot screenshots",
		description = "Attach a game-window screenshot to qualifying loot-flex posts. Visible game chat may be included.",
		warning = "Screenshots can include visible game chat and are uploaded to the SwampScape Discord server",
		position = 8
	)
	default boolean lootScreenshots()
	{
		return false;
	}

	@Range(min = 1, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "lootThreshold",
		name = "Loot threshold (gp)",
		description = "Minimum combined estimated GE value for the full drop. The server also enforces its own threshold.",
		position = 9
	)
	default int lootThreshold()
	{
		return 500_000;
	}
}
