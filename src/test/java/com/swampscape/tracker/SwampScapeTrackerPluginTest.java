package com.swampscape.tracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SwampScapeTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SwampScapeTrackerPlugin.class);
		RuneLite.main(args);
	}
}
