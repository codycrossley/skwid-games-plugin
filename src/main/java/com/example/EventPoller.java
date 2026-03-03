package com.example;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class EventPoller
{
    public interface Listener
    {
        void onEvent(RelayClient.EventOut e);
        void onError(Exception e);
    }

    private final RelayClient relay;
    private final Listener listener;

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r ->
            {
                Thread t = new Thread(r, "skwid-relay-poller");
                t.setDaemon(true);
                return t;
            });

    private volatile String gameId;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private volatile boolean running = false;

    // Handle for the scheduled task so we can cancel it
    private volatile ScheduledFuture<?> scheduled;

    public EventPoller(RelayClient relay, Listener listener)
    {
        this.relay = relay;
        this.listener = listener;
    }

    public synchronized void start(String gameId)
    {
        // Restart safely
        stop();

        this.gameId = gameId;
        this.lastSeq.set(0);
        this.running = true;

        // Store the future so it can be cancelled later
        scheduled = exec.scheduleAtFixedRate(this::tick, 0, 500, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop()
    {
        running = false;

        // Cancel scheduled polling task so it doesn't keep firing forever
        if (scheduled != null)
        {
            scheduled.cancel(false); // Don't interrupt if tick is currently running
            scheduled = null;
        }
    }

    public synchronized boolean isRunning()
    {
        return running;
    }

    public synchronized void shutdown()
    {
        stop();
        exec.shutdownNow();
    }

    private void tick()
    {
        if (!running) return;

        final String gid = this.gameId;
        if (gid == null || gid.isBlank()) return;

        try
        {
            int after = lastSeq.get();
            RelayClient.ReadEventsResponse resp = relay.readEvents(gid, after);

            for (RelayClient.EventOut e : resp.events)
            {
                // Advance seq monotonically
                lastSeq.set(Math.max(lastSeq.get(), e.seq));
                listener.onEvent(e);
            }
        }
        catch (Exception e)
        {
            listener.onError(e);
        }
    }
}