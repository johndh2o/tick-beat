package com.tickbeat;

/**
 * A smoothed estimate of the game-tick grid.
 *
 * OSRS server ticks are exactly 600 ms apart, but the client sees them with
 * network jitter. Instead of snapping to every raw tick (which would make the
 * music wobble), this keeps a fixed 600 ms grid and nudges its phase a little
 * toward each tick that arrives (a simple phase-locked loop).
 *
 * When no ticks arrive (logged out, loading), the grid just free-runs.
 */
final class TickClock
{
	static final double PERIOD_NS = 600_000_000.0;

	/** How strongly each tick pulls the grid (0..1). Lower = smoother, slower to react. */
	private static final double ALPHA = 0.10;
	/** A tick this far off the grid means a reconnect or a big lag spike, so re-anchor. */
	private static final double REANCHOR_NS = 220_000_000.0;
	/** After this long without ticks, the next tick re-anchors the grid. */
	private static final long STALE_NS = 5_000_000_000L;

	private double anchor = Double.NaN;
	private long lastTick;
	private boolean gotTick;

	synchronized void onTick(long nowNs)
	{
		if (!gotTick || Double.isNaN(anchor) || nowNs - lastTick > STALE_NS)
		{
			anchor = nowNs;
		}
		else
		{
			double n = Math.rint((nowNs - anchor) / PERIOD_NS);
			double gridTime = anchor + n * PERIOD_NS;
			double err = nowNs - gridTime;
			if (Math.abs(err) > REANCHOR_NS)
			{
				anchor = nowNs;
			}
			else
			{
				// Keep the anchor close to "now" so the numbers stay small and precise.
				anchor = gridTime + ALPHA * err;
			}
		}
		lastTick = nowNs;
		gotTick = true;
	}

	/** Position inside the current tick at the given time, 0.0 (on the tick) to just under 1.0. */
	synchronized double phase(long timeNs)
	{
		if (Double.isNaN(anchor))
		{
			anchor = timeNs;
		}
		double x = (timeNs - anchor) / PERIOD_NS;
		return x - Math.floor(x);
	}

	synchronized boolean hasTicks()
	{
		return gotTick;
	}
}
