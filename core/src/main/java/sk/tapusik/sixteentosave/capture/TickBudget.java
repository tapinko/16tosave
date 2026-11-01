package sk.tapusik.sixteentosave.capture;

import java.util.function.LongSupplier;

/**
 * Time budget for work done on the client thread within one tick. The first unit of work is
 * always allowed, so progress never stalls even with a tiny budget.
 */
public final class TickBudget {
	private final LongSupplier nanoClock;
	private final long budgetNanos;
	private long start;
	private boolean usedAny;

	public TickBudget(long budgetNanos, LongSupplier nanoClock) {
		this.budgetNanos = Math.max(0, budgetNanos);
		this.nanoClock = nanoClock;
	}

	public static TickBudget ofMicros(long micros) {
		return new TickBudget(micros * 1_000L, System::nanoTime);
	}

	public TickBudget start() {
		start = nanoClock.getAsLong();
		usedAny = false;
		return this;
	}

	/** Call before each unit of work; returns {@code false} once the budget is spent. */
	public boolean tryUse() {
		if (!usedAny) {
			usedAny = true;
			return true;
		}

		return nanoClock.getAsLong() - start < budgetNanos;
	}
}
