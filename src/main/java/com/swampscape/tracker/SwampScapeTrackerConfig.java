package com.swampscape.tracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("swampscapeTracker")
public interface SwampScapeTrackerConfig extends Config
{
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
		description = "The server URL supplied by the Discord bot.",
		position = 1
	)
	default String serverUrl()
	{
		return "http://127.0.0.1:8787";
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

	@Range(min = 1, max = Integer.MAX_VALUE)
	@ConfigItem(
		keyName = "lootThreshold",
		name = "Loot threshold (gp)",
		description = "Minimum estimated GE value for a loot stack. The server also enforces its own threshold.",
		position = 8
	)
	default int lootThreshold()
	{
		return 500_000;
	}
}
