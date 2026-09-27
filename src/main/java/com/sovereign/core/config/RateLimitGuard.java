package com.sovereign.core.config;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * <b>RateLimitGuard</b>
 *
 * <p>Protects the user from ever exceeding Gemini's 100% free tier quota:</p>
 * <ul>
 *   <li>15 Requests Per Minute (RPM) — rolling 60-second window</li>
 *   <li>1,500 Requests Per Day (RPD) — resets at midnight</li>
 * </ul>
 *
 * <p>When the rolling window nears capacity (e.g. 14+ RPM or 1500 RPD),
 * {@link #shouldFallbackToOllama()} triggers an automatic, zero-cost routing to the
 * local Ollama engine or in-memory fallback, guaranteeing zero bills.</p>
 */
public class RateLimitGuard {

    private static final Logger LOG = Logger.getLogger(RateLimitGuard.class.getName());

    public static final int MAX_REQUESTS_PER_MINUTE = 15;
    public static final int MAX_REQUESTS_PER_DAY = 1500;
    public static final int RPM_SAFETY_THRESHOLD = 14;

    private static final RateLimitGuard INSTANCE = new RateLimitGuard();

    private final Deque<Long> requestTimestamps = new ArrayDeque<>();
    private LocalDate currentDay = LocalDate.now();
    private int dailyCallCount = 0;

    public RateLimitGuard() {}

    public static RateLimitGuard getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a Gemini call is permitted under the 15 RPM and 1500 RPD free tier constraints.
     */
    public synchronized boolean canCallGemini() {
        pruneOldTimestamps(System.currentTimeMillis());
        rolloverDayIfNeeded();
        return requestTimestamps.size() < MAX_REQUESTS_PER_MINUTE && dailyCallCount < MAX_REQUESTS_PER_DAY;
    }

    /**
     * Recommends falling back to local Ollama if near or exceeding the free tier rate limit.
     */
    public synchronized boolean shouldFallbackToOllama() {
        pruneOldTimestamps(System.currentTimeMillis());
        rolloverDayIfNeeded();
        return requestTimestamps.size() >= RPM_SAFETY_THRESHOLD || dailyCallCount >= MAX_REQUESTS_PER_DAY;
    }

    /**
     * Records a completed or initiated Gemini request.
     */
    public synchronized void recordGeminiCall() {
        long now = System.currentTimeMillis();
        pruneOldTimestamps(now);
        rolloverDayIfNeeded();

        requestTimestamps.addLast(now);
        dailyCallCount++;

        if (requestTimestamps.size() >= RPM_SAFETY_THRESHOLD) {
            LOG.warning("[RATE LIMIT GUARD] Free tier warning: " + requestTimestamps.size()
                    + "/" + MAX_REQUESTS_PER_MINUTE + " RPM used. Routing overflow to Ollama.");
        }
    }

    public synchronized int getCallsThisMinute() {
        pruneOldTimestamps(System.currentTimeMillis());
        return requestTimestamps.size();
    }

    public synchronized int getRemainingCallsThisMinute() {
        pruneOldTimestamps(System.currentTimeMillis());
        return Math.max(0, MAX_REQUESTS_PER_MINUTE - requestTimestamps.size());
    }

    public synchronized int getDailyCallCount() {
        rolloverDayIfNeeded();
        return dailyCallCount;
    }

    public synchronized int getRemainingCallsToday() {
        rolloverDayIfNeeded();
        return Math.max(0, MAX_REQUESTS_PER_DAY - dailyCallCount);
    }

    public synchronized void reset() {
        requestTimestamps.clear();
        dailyCallCount = 0;
        currentDay = LocalDate.now();
    }

    private void pruneOldTimestamps(long now) {
        long oneMinuteAgo = now - 60_000L;
        while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst() < oneMinuteAgo) {
            requestTimestamps.pollFirst();
        }
    }

    private void rolloverDayIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            currentDay = today;
            dailyCallCount = 0;
            LOG.info("[RATE LIMIT GUARD] New day detected (" + today + "). Daily quota reset to 1,500 calls.");
        }
    }
}
