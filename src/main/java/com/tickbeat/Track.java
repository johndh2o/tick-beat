package com.tickbeat;

import net.runelite.client.util.Filepath;

/** One playlist entry: an audio file, its tempo, and where a beat falls in it. */
final class Track
{
	final Filepath file;
	final double bpm;
	/** Where a beat falls in the file. Changed by the "Flip beat" button. */
	volatile double firstBeatMs;

	Track(Filepath file, double bpm, double firstBeatMs)
	{
		this.file = file;
		this.bpm = bpm;
		this.firstBeatMs = firstBeatMs;
	}

	String fileName()
	{
		return file.getFileName();
	}

	String name()
	{
		String n = fileName();
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	/**
	 * How many musical beats fit in one 600 ms game tick, snapped to 0.5, 1, 2 or 4.
	 * 100 BPM = 1 beat per tick, 200 BPM = 2, 50 BPM = half a beat (one beat every two ticks).
	 */
	double beatsPerTick()
	{
		double ratio = bpm / 100.0;
		double[] options = {0.5, 1, 2, 4};
		double best = 1;
		double bestDist = Double.MAX_VALUE;
		for (double o : options)
		{
			double d = Math.abs(Math.log(ratio / o));
			if (d < bestDist)
			{
				bestDist = d;
				best = o;
			}
		}
		return best;
	}

	/** Length of one game tick in this song's own time, in ms (600 ms at exactly 100 BPM). */
	double tickLengthMs()
	{
		return beatsPerTick() * 60000.0 / bpm;
	}

	/** Speed the song has to play at so its beats land exactly on ticks (1.0 = untouched). */
	double requiredSpeed()
	{
		return 100.0 * beatsPerTick() / bpm;
	}
}
