package sk.tapusik.sixteentosave.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sk.tapusik.sixteentosave.SixteenToSave;

/** WARN logging at most once per interval per key, with a count of what was suppressed in between. */
public final class RateLimitedLog {
	private static final long INTERVAL_NANOS = 30_000_000_000L;

	private record State(AtomicLong lastNanos, AtomicLong suppressed) {
	}

	private static final Map<String, State> STATES = new ConcurrentHashMap<>();

	private RateLimitedLog() {
	}

	public static void warn(String key, String message, Object... args) {
		State state = STATES.computeIfAbsent(key, k -> new State(new AtomicLong(Long.MIN_VALUE / 2), new AtomicLong()));
		long now = System.nanoTime();
		long last = state.lastNanos.get();

		if (now - last >= INTERVAL_NANOS && state.lastNanos.compareAndSet(last, now)) {
			long suppressed = state.suppressed.getAndSet(0);
			String suffix = suppressed > 0 ? " (" + suppressed + " similar messages suppressed)" : "";
			SixteenToSave.LOGGER.warn(message + suffix, args);
		} else {
			state.suppressed.incrementAndGet();
		}
	}
}
