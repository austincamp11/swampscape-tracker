package com.swampscape.tracker;

import com.google.gson.Gson;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
final class TrackerClient
{
	private static final int MAX_QUEUE_SIZE = 500;
	private static final int MAX_BATCH_SIZE = 100;
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final SwampScapeTrackerConfig config;
	private final Deque<TrackerEvent> pending = new ArrayDeque<>();
	private final AtomicBoolean uploadInProgress = new AtomicBoolean();

	@Inject
	TrackerClient(OkHttpClient httpClient, Gson gson, SwampScapeTrackerConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	synchronized void enqueue(TrackerEvent event)
	{
		if (pending.size() >= MAX_QUEUE_SIZE)
		{
			pending.removeFirst();
			log.warn("SwampScape tracker queue was full; the oldest activity was discarded");
		}
		pending.addLast(event);
	}

	void flush(String rsn, String pluginVersion)
	{
		if (!config.shareActivity() || isBlank(rsn) || isBlank(config.connectionToken()) || isBlank(config.serverUrl()))
		{
			return;
		}
		if (!uploadInProgress.compareAndSet(false, true))
		{
			return;
		}

		final List<TrackerEvent> batch = snapshot();
		if (batch.isEmpty())
		{
			uploadInProgress.set(false);
			return;
		}

		final String endpoint = config.serverUrl().trim().replaceAll("/+$", "") + "/v1/events";
		final Request request;
		try
		{
			request = new Request.Builder()
				.url(endpoint)
				.header("Authorization", "Bearer " + config.connectionToken().trim())
				.header("User-Agent", "SwampScapeRuneLiteTracker/" + pluginVersion)
				.post(RequestBody.create(JSON, gson.toJson(new TrackerBatch(rsn, pluginVersion, batch))))
				.build();
		}
		catch (IllegalArgumentException error)
		{
			uploadInProgress.set(false);
			log.warn("SwampScape tracker server URL is invalid");
			return;
		}

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException error)
			{
				uploadInProgress.set(false);
				log.debug("SwampScape tracker upload failed; queued activity will be retried", error);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response ignored = response)
				{
					if (response.isSuccessful())
					{
						removeUploaded(batch);
					}
					else
					{
						log.debug("SwampScape tracker server returned HTTP {}", response.code());
					}
				}
				finally
				{
					uploadInProgress.set(false);
				}
			}
		});
	}

	private synchronized List<TrackerEvent> snapshot()
	{
		List<TrackerEvent> result = new ArrayList<>(Math.min(pending.size(), MAX_BATCH_SIZE));
		int index = 0;
		for (TrackerEvent event : pending)
		{
			if (index++ >= MAX_BATCH_SIZE)
			{
				break;
			}
			result.add(event);
		}
		return result;
	}

	private synchronized void removeUploaded(List<TrackerEvent> uploaded)
	{
		Set<String> identifiers = new HashSet<>();
		for (TrackerEvent event : uploaded)
		{
			identifiers.add(event.getEventId());
		}
		pending.removeIf(event -> identifiers.contains(event.getEventId()));
	}

	private static boolean isBlank(String value)
	{
		return value == null || value.trim().isEmpty();
	}
}
