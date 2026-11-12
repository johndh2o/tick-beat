package com.tickbeat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import net.runelite.client.util.Filepath;

/**
 * Offline check of the sync engine, no game or sound card needed.
 *
 * Makes a click track at an "off" tempo, plays it through the engine with fake
 * game ticks that arrive with network jitter, and measures how far each audible
 * click lands from the true server tick.
 *
 * Run: java com.tickbeat.SyncSimulation
 */
public final class SyncSimulation
{
	static final float SR = 44100f;

	public static void main(String[] args) throws Exception
	{
		boolean ok = true;
		ok &= run(98.0, 250, 30);
		ok &= run(101.5, 0, 30);
		ok &= run(196.0, 1234, 30);
		ok &= run(50.8, 80, 40);
		ok &= run(100.0, 300, 30, 80);   // music offset slider moved to +80 ms mid-song
		ok &= run(99.0, 0, 30, -120);
		ok &= clickOnly();
		System.out.println(ok ? "ALL PASSED" : "FAILED");
		if (!ok)
		{
			System.exit(1);
		}
	}

	/** Returns true if, after settling, beats stay within 20 ms of the true tick. */
	static boolean run(double bpm, double firstBeatMs, double jitterMs) throws Exception
	{
		return run(bpm, firstBeatMs, jitterMs, 0);
	}

	static boolean run(double bpm, double firstBeatMs, double jitterMs, int offsetMs) throws Exception
	{
		File wav = clickTrack(bpm, firstBeatMs, 90);
		FakeOut out = new FakeOut();
		TickClock ticks = new TickClock();
		BeatSyncPlayer p = new BeatSyncPlayer(out, out::nowNs, ticks, System.out::println);
		p.setPlaylist(Collections.singletonList(new Track(asFilepath(wav), bpm, firstBeatMs)), false);
		p.setPlaying(true);
		p.setVolume(100);

		Random rnd = new Random(42);
		double tickOffsetNs = 137_000_000; // server grid is at an arbitrary phase vs. our clock
		long nextTick = 0;
		long seenTick = (long) (tickOffsetNs + rnd.nextGaussian() * jitterMs * 1e6);

		// Play ~80 s of audio.
		while (out.written < SR * 80)
		{
			while (out.nowNs() >= seenTick)
			{
				ticks.onTick(seenTick);
				nextTick++;
				seenTick = (long) (tickOffsetNs + nextTick * TickClock.PERIOD_NS
					+ rnd.nextGaussian() * jitterMs * 1e6);
			}
			if (offsetMs != 0 && out.written >= SR * 10 && out.written < SR * 10 + BeatSyncPlayer.CHUNK)
			{
				p.setOffsetMs(offsetMs);
			}
			p.step();
		}

		// Find click onsets in what was "heard", compare with the true tick grid.
		short[] s = out.samples();
		List<Double> errs = new ArrayList<>();
		int lastOnset = -100000;
		for (int i = 0; i < s.length; i++)
		{
			if (Math.abs(s[i]) > 8000 && i - lastOnset > SR * 0.1)
			{
				lastOnset = i;
				double tNs = i / (double) SR * 1e9 - offsetMs * 1e6;
				double phase = (tNs - tickOffsetNs) / TickClock.PERIOD_NS;
				double e = (phase - Math.rint(phase)) * 600.0;
				// For 50 BPM a click falls every other tick; for 200 BPM half the clicks are between ticks.
				double bpt = new Track(asFilepath(wav), bpm, 0).beatsPerTick();
				if (bpt > 1)
				{
					double sub = (tNs - tickOffsetNs) / (TickClock.PERIOD_NS / bpt);
					e = (sub - Math.rint(sub)) * 600.0 / bpt;
				}
				if (offsetMs == 0 || i > SR * 11)
				{
					errs.add(e);
				}
			}
		}
		List<Double> settled = errs.subList(errs.size() / 4, errs.size());
		// With an offset change at 10 s, judge every beat from 1 s after it: the jump must be immediate.
		if (offsetMs != 0)
		{
			settled = errs;
		}
		double worst = 0, sum = 0;
		for (double e : settled)
		{
			worst = Math.max(worst, Math.abs(e));
			sum += Math.abs(e);
		}
		double avg = sum / settled.size();
		boolean pass = worst < 20 && settled.size() > 20;
		System.out.printf("%6.1f BPM, first beat %4.0f ms, jitter %2.0f ms, offset %+4d ms -> %d beats, avg off %.1f ms, worst %.1f ms  %s%n",
			bpm, firstBeatMs, jitterMs, offsetMs, settled.size(), avg, worst, pass ? "OK" : "FAIL");
		wav.delete();
		return pass;
	}

	/** No songs, synced click on: it should run as a plain metronome, on the tick. */
	static boolean clickOnly() throws Exception
	{
		FakeOut out = new FakeOut();
		TickClock ticks = new TickClock();
		BeatSyncPlayer p = new BeatSyncPlayer(out, out::nowNs, ticks, System.out::println);
		p.setPlaylist(Collections.<Track>emptyList(), false);
		p.setClickVolume(100);
		p.setPlaying(true);
		double tickOffsetNs = 250_000_000;
		long k = 0;
		while (out.written < SR * 30)
		{
			while (out.nowNs() >= tickOffsetNs + k * TickClock.PERIOD_NS)
			{
				ticks.onTick((long) (tickOffsetNs + k * TickClock.PERIOD_NS));
				k++;
			}
			p.step();
		}
		short[] s = out.samples();
		double worst = 0;
		int n = 0, last = -100000;
		for (int i = (int) (SR * 5); i < s.length; i++)
		{
			if (Math.abs(s[i]) > 3000 && i - last > SR * 0.1)
			{
				last = i;
				double ph = (i / (double) SR * 1e9 - tickOffsetNs) / TickClock.PERIOD_NS;
				worst = Math.max(worst, Math.abs(ph - Math.rint(ph)) * 600);
				n++;
			}
		}
		boolean pass = n > 35 && worst < 3;
		System.out.printf("metronome only (no songs)                        -> %d clicks, worst %.1f ms  %s%n", n, worst, pass ? "OK" : "FAIL");
		return pass;
	}

	static Filepath asFilepath(File f)
	{
		return Filepath.Unchecked.getRooted(f.getParentFile().toPath()).joinSegment(f.getName());
	}

	static File clickTrack(double bpm, double firstBeatMs, int seconds) throws Exception
	{
		int frames = (int) (SR * seconds);
		byte[] data = new byte[frames * 4];
		double beat = 60.0 / bpm * SR;
		for (double t = firstBeatMs / 1000 * SR; t < frames; t += beat)
		{
			for (int k = 0; k < 200; k++)
			{
				int i = (int) t + k;
				if (i >= frames)
				{
					break;
				}
				short v = (short) (20000 * Math.exp(-k / 60.0));
				for (int c = 0; c < 2; c++)
				{
					data[(i * 2 + c) * 2] = (byte) v;
					data[(i * 2 + c) * 2 + 1] = (byte) (v >> 8);
				}
			}
		}
		AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
		File f = File.createTempFile("click", ".wav");
		AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), fmt, frames),
			AudioFileFormat.Type.WAVE, f);
		return f;
	}

	/** Pretends to be a sound card with a 2048-frame buffer; time advances as audio plays. */
	static final class FakeOut implements AudioOut
	{
		static final int BUFFER = 2048;
		long written;
		int channels = 2;
		private final java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();

		@Override
		public void open(float sampleRate, int ch)
		{
			channels = ch;
		}

		@Override
		public void write(byte[] data, int length)
		{
			all.write(data, 0, length);
			written += length / (2 * channels);
		}

		@Override
		public long framesPlayed()
		{
			return Math.max(0, written - BUFFER);
		}

		long nowNs()
		{
			return (long) (framesPlayed() / (double) SR * 1e9);
		}

		@Override
		public void close()
		{
		}

		/** The left channel, indexed by the frame at which it was heard. */
		short[] samples()
		{
			byte[] b = all.toByteArray();
			short[] s = new short[b.length / (2 * channels)];
			for (int i = 0; i < s.length; i++)
			{
				int j = i * 2 * channels;
				s[i] = (short) ((b[j] & 0xff) | (b[j + 1] << 8));
			}
			return s;
		}
	}
}
