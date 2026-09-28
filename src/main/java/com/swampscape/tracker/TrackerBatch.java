package com.swampscape.tracker;

import java.util.List;

final class TrackerBatch
{
	private final String rsn;
	private final String pluginVersion;
	private final List<TrackerEvent> events;

	TrackerBatch(String rsn, String pluginVersion, List<TrackerEvent> events)
	{
		this.rsn = rsn;
		this.pluginVersion = pluginVersion;
		this.events = events;
	}
}
