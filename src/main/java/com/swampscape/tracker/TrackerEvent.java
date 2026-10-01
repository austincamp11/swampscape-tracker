package com.swampscape.tracker;

final class TrackerEvent
{
	private final String eventId;
	private final String type;
	private final String killType;
	private final int value;
	private final Integer quantity;
	private final Integer itemId;
	private final String itemName;
	private final String npcName;
	private final Long deathLossValue;
	private final String screenshotBase64;
	private final String occurredAt;

	private TrackerEvent(
		String type,
		String killType,
		int value,
		Integer quantity,
		Integer itemId,
		String itemName,
		String npcName,
		Long deathLossValue,
		String screenshotBase64,
		String occurredAt,
		String eventId)
	{
		this.type = type;
		this.killType = killType;
		this.value = value;
		this.quantity = quantity;
		this.itemId = itemId;
		this.itemName = itemName;
		this.npcName = npcName;
		this.deathLossValue = deathLossValue;
		this.screenshotBase64 = screenshotBase64;
		this.occurredAt = occurredAt;
		this.eventId = eventId;
	}

	static TrackerEvent gameTime(int seconds)
	{
		return create("SESSION_TIME", null, seconds, null, null, null, null);
	}

	static TrackerEvent kill(String target, String killType)
	{
		return create("KILL", killType, 1, null, null, null, target);
	}

	static TrackerEvent death(long lostValue, String killedBy, String screenshotBase64)
	{
		return new TrackerEvent(
			"DEATH",
			null,
			1,
			null,
			null,
			null,
			killedBy,
			Math.max(0L, lostValue),
			screenshotBase64,
			java.time.Instant.now().toString(),
			java.util.UUID.randomUUID().toString());
	}

	static TrackerEvent loot(
		int value,
		int quantity,
		int itemId,
		String itemName,
		String source,
		String screenshotBase64)
	{
		return new TrackerEvent(
			"LOOT",
			null,
			value,
			quantity,
			itemId,
			itemName,
			source,
			null,
			screenshotBase64,
			java.time.Instant.now().toString(),
			java.util.UUID.randomUUID().toString());
	}

	private static TrackerEvent create(
		String type,
		String killType,
		int value,
		Integer quantity,
		Integer itemId,
		String itemName,
		String npcName)
	{
		return new TrackerEvent(
			type,
			killType,
			value,
			quantity,
			itemId,
			itemName,
			npcName,
			null,
			null,
			java.time.Instant.now().toString(),
			java.util.UUID.randomUUID().toString());
	}

	String getEventId()
	{
		return eventId;
	}
}
