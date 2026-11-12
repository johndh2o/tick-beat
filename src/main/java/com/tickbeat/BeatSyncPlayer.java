package com.tickbeat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Plays a playlist with every beat locked to the game tick.
 *
 * How the sync works:
 *  1. Each song plays at the small speed change needed to hit exactly 100 BPM
 *     (or 50/200), e.g. a 98 BPM song runs about 2% faster.
 *  2. When a song starts, its first downbeat is lined up with an upcoming tick.
 *  3. While playing, the engine keeps comparing "where in the beat the listener
 *     is hearing right now" with "where in the tick the game is", and makes
 *     tiny speed adjustments (at most +/-2%) to close any gap. A large gap
 *     (lag spike, reconnect) gets fixed with a single jump instead.
 */
final class BeatSyncPlayer
{
	static final int CHUNK = 512;

	/** Speed correction per unit of phase error (in ticks). */
	private static final double KP = 0.25;
	/** Largest speed correction allowed while tracking. */
	private static final double MAX_CORRECTION = 0.02;
	/** Errors larger than this (in ticks, 0.12 = 72 ms) get fixed with a jump. */
	private static final double HARD_RESYNC = 0.12;

	private final AudioOut out;
	private final LongSupplier clock;
	private final TickClock ticks;
	private final Consumer<String> messages;

	// Settings, written from the client thread.
	private volatile float gain = 0.7f;
	private volatile float clickGain = 0f;
	private volatile long offsetNs = 0;
	private volatile double maxStretch = 0.05;
	private volatile boolean playing = false;
	private volatile boolean skipRequested = false;
	private volatile boolean beatChanged = false;
	private volatile List<Track> pendingPlaylist = null;
	private volatile boolean pendingShuffle = false;

	// Status, read by the overlay.
	private volatile String nowPlaying = null;
	private volatile double lastErrorMs = 0;

	// Audio-thread state.
	private List<Track> playlist = new ArrayList<>();
	private int index = -1;
	private Decoded song;
	private Track track;
	private double songTickFrames;
	private double firstBeatFrame;
	private double baseRate;
	private double pos;
	private boolean needsAlign;
	private long outFrames;
	private long cooldownUntil;
	private float openRate = -1;
	private int openChannels = -1;
	private final ArrayDeque<double[]> written = new ArrayDeque<>();
	private byte[] buf = new byte[CHUNK * 4];

	private Thread thread;
	private volatile boolean running;

	BeatSyncPlayer(AudioOut out, LongSupplier clock, TickClock ticks, Consumer<String> messages)
	{
		this.out = out;
		this.clock = clock;
		this.ticks = ticks;
		this.messages = messages;
	}

	// ---------------------------------------------------------------- controls

	void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		thread = new Thread(this::loop, "tick-beat-audio");
		thread.setDaemon(true);
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
	}

	void shutdown()
	{
		running = false;
		if (thread != null)
		{
			thread.interrupt();
			try
			{
				thread.join(1000);
			}
			catch (InterruptedException ignored)
			{
				Thread.currentThread().interrupt();
			}
			thread = null;
		}
		out.close();
	}

	void setPlaylist(List<Track> tracks, boolean shuffle)
	{
		pendingShuffle = shuffle;
		pendingPlaylist = new ArrayList<>(tracks);
	}

	void setPlaying(boolean p)
	{
		playing = p;
	}

	void next()
	{
		skipRequested = true;
	}

	void setVolume(int percent)
	{
		float v = Math.max(0, Math.min(100, percent)) / 100f;
		gain = v * v; // perceptual curve
	}

	/** Volume of the built-in synced click, 0 turns it off. */
	void setClickVolume(int percent)
	{
		float v = Math.max(0, Math.min(100, percent)) / 100f;
		clickGain = v * v;
	}

	private final java.util.concurrent.atomic.AtomicLong pendingShiftNs = new java.util.concurrent.atomic.AtomicLong();

	void setOffsetMs(int ms)
	{
		long n = ms * 1_000_000L;
		pendingShiftNs.addAndGet(n - offsetNs);
		offsetNs = n;
	}

	void setMaxStretchPercent(int percent)
	{
		maxStretch = percent / 100.0;
	}

	/** The song playing right now, or null. */
	Track currentTrack()
	{
		return nowPlayingTrack;
	}

	/** Call after changing the current track's firstBeatMs: the music re-locks to the new beat. */
	void beatPositionChanged()
	{
		beatChanged = true;
	}

	private volatile Track nowPlayingTrack;

	String nowPlaying()
	{
		return nowPlaying;
	}

	double lastErrorMs()
	{
		return lastErrorMs;
	}

	// ---------------------------------------------------------------- engine

	private void loop()
	{
		while (running)
		{
			try
			{
				if (!playing)
				{
					if (song != null)
					{
						unload();
						out.close();
						openRate = -1;
					}
					Thread.sleep(25);
					continue;
				}
				step();
			}
			catch (InterruptedException e)
			{
				return;
			}
			catch (Exception e)
			{
				messages.accept("Tick Beat audio error: " + e.getMessage());
				playing = false;
			}
		}
	}

	/** Renders and writes one chunk. Public to the package so tests can drive it. */
	void step() throws Exception
	{
		List<Track> np = pendingPlaylist;
		if (np != null)
		{
			pendingPlaylist = null;
			playlist = np;
			if (pendingShuffle)
			{
				Collections.shuffle(playlist);
			}
			index = -1;
			unload();
		}
		if (beatChanged)
		{
			beatChanged = false;
			if (song != null)
			{
				// The engine sees the beat is now half a tick away and jumps to it.
				firstBeatFrame = track.firstBeatMs / 1000.0 * song.sampleRate;
			}
		}
		if (skipRequested)
		{
			skipRequested = false;
			unload();
		}
		if (song == null && !loadNext())
		{
			if (openRate < 0 && clickGain > 0)
			{
				// No songs, but the click is on: run as a plain metronome.
				out.open(44100f, 2);
				openRate = 44100f;
				openChannels = 2;
				outFrames = 0;
				written.clear();
			}
			if (openRate < 0)
			{
				Thread.sleep(100);
				return;
			}
			writeSilence();
			return;
		}
		if (needsAlign)
		{
			align();
			pendingShiftNs.set(0);
		}
		long shift = pendingShiftNs.getAndSet(0);
		if (shift != 0)
		{
			// Offset moved: jump right away instead of drifting there slowly.
			pos -= shift / 1e9 * song.sampleRate * baseRate;
			cooldownUntil = outFrames;
		}

		double rate = baseRate;
		long played = out.framesPlayed();
		double heard = heardSongPosition(played);
		if (!Double.isNaN(heard) && played >= cooldownUntil)
		{
			double songPhase = frac((heard - firstBeatFrame) / songTickFrames);
			double tickPhase = ticks.phase(clock.getAsLong() - offsetNs);
			double err = wrap(songPhase - tickPhase); // > 0 means the music is early
			lastErrorMs = err * TickClock.PERIOD_NS / 1e6;
			if (Math.abs(err) > HARD_RESYNC)
			{
				pos -= err * songTickFrames;
				cooldownUntil = outFrames; // wait until the jump is audible before measuring again
			}
			else
			{
				double c = Math.max(-MAX_CORRECTION, Math.min(MAX_CORRECTION, KP * err));
				rate = baseRate * (1 - c);
			}
		}

		render(rate);

		if (pos >= song.frames)
		{
			unload();
		}
	}

	private boolean loadNext()
	{
		if (playlist.isEmpty())
		{
			return false;
		}
		for (int attempt = 0; attempt < playlist.size(); attempt++)
		{
			index = (index + 1) % playlist.size();
			Track t = playlist.get(index);
			double speed = t.requiredSpeed();
			if (Math.abs(speed - 1) > maxStretch + 1e-9)
			{
				messages.accept(String.format("Tick Beat: skipping %s (%.1f BPM would need a %.1f%% speed change)",
					t.name(), t.bpm, (speed - 1) * 100));
				continue;
			}
			try
			{
				Decoded d = Decoded.load(t.file);
				if (d.sampleRate != openRate || d.channels != openChannels)
				{
					out.open(d.sampleRate, d.channels);
					openRate = d.sampleRate;
					openChannels = d.channels;
					outFrames = 0;
					cooldownUntil = 0;
					written.clear();
				}
				song = d;
				track = t;
				songTickFrames = t.beatsPerTick() * 60.0 / t.bpm * d.sampleRate;
				firstBeatFrame = t.firstBeatMs / 1000.0 * d.sampleRate;
				baseRate = songTickFrames / (TickClock.PERIOD_NS / 1e9 * d.sampleRate);
				needsAlign = true;
				nowPlaying = t.name();
				nowPlayingTrack = t;
				if (buf.length < CHUNK * 2 * d.channels)
				{
					buf = new byte[CHUNK * 2 * d.channels];
				}
				return true;
			}
			catch (Exception e)
			{
				messages.accept("Tick Beat: couldn't play " + t.file.getName() + " (" + e.getMessage() + ")");
			}
		}
		return false;
	}

	private void unload()
	{
		song = null;
		track = null;
		nowPlaying = null;
		nowPlayingTrack = null;
	}

	/** Pick a start position so the song's beat grid lands on the tick grid. */
	private void align()
	{
		needsAlign = false;
		long played = out.framesPlayed();
		double latencySec = Math.max(0, outFrames - played) / (double) song.sampleRate;
		long hearTime = clock.getAsLong() + (long) (latencySec * 1e9) - offsetNs;
		double p = ticks.phase(hearTime);
		// A song position whose beat-phase equals p, at most one tick before the start of the file.
		double x = firstBeatFrame + p * songTickFrames;
		pos = x - Math.ceil(x / songTickFrames) * songTickFrames;
		cooldownUntil = outFrames;
	}

	/** Song position of the frame coming out of the speakers right now. */
	private double heardSongPosition(long played)
	{
		while (written.size() > 1 && written.peekFirst()[0] + CHUNK <= played)
		{
			written.pollFirst();
		}
		double[] c = written.peekFirst();
		if (c == null || played < c[0] || played >= c[0] + CHUNK)
		{
			return Double.NaN;
		}
		return c[1] + (played - c[0]) * c[2];
	}

	private void render(double rate)
	{
		written.addLast(new double[]{outFrames, pos, rate});
		int ch = song.channels;
		short[] pcm = song.pcm;
		long frames = song.frames;
		float g = gain;
		int b = 0;
		for (int i = 0; i < CHUNK; i++)
		{
			long i0 = (long) Math.floor(pos);
			double f = pos - i0;
			for (int c = 0; c < ch; c++)
			{
				double s0 = (i0 >= 0 && i0 < frames) ? pcm[(int) (i0 * ch + c)] : 0;
				double s1 = (i0 + 1 >= 0 && i0 + 1 < frames) ? pcm[(int) ((i0 + 1) * ch + c)] : 0;
				int v = (int) Math.round((s0 + (s1 - s0) * f) * g);
				v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
				buf[b++] = (byte) v;
				buf[b++] = (byte) (v >> 8);
			}
			pos += rate;
		}
		mixClick(ch, song.sampleRate);
		outFrames += CHUNK;
		out.write(buf, b);
	}

	private void writeSilence()
	{
		int len = CHUNK * 2 * openChannels;
		java.util.Arrays.fill(buf, 0, len, (byte) 0);
		mixClick(openChannels, openRate);
		outFrames += CHUNK;
		written.clear();
		out.write(buf, len);
	}

	// ---------------------------------------------------------------- metronome click

	private float[] clickTable;
	private float clickTableRate = -1;
	private int clickPos = Integer.MAX_VALUE;
	private double lastClickPhase = 0;

	/**
	 * Adds a metronome click on every tick, timed with the same grid and the same
	 * offset as the music, so the click and the beat always land together.
	 */
	private void mixClick(int ch, float sr)
	{
		float g = clickGain;
		if (g <= 0)
		{
			clickPos = Integer.MAX_VALUE;
			return;
		}
		if (clickTableRate != sr)
		{
			int len = (int) (sr * 0.04);
			clickTable = new float[len];
			for (int i = 0; i < len; i++)
			{
				double t = i / sr;
				clickTable[i] = (float) (Math.sin(2 * Math.PI * 1760 * t) * Math.exp(-t / 0.008) * 14000);
			}
			clickTableRate = sr;
		}
		long played = out.framesPlayed();
		double latencySec = Math.max(0, outFrames - played) / (double) sr;
		double phase = ticks.phase(clock.getAsLong() + (long) (latencySec * 1e9) - offsetNs);
		double stepPerFrame = 1.0 / (TickClock.PERIOD_NS / 1e9 * sr);
		if (phase < lastClickPhase - 0.5)
		{
			clickPos = 0; // the tick fell between the last chunk and this one
		}
		for (int i = 0; i < CHUNK; i++)
		{
			if (clickPos < clickTable.length)
			{
				float s = clickTable[clickPos++] * g;
				for (int c = 0; c < ch; c++)
				{
					int j = (i * ch + c) * 2;
					int v = (short) ((buf[j] & 0xff) | (buf[j + 1] << 8));
					v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v + s)));
					buf[j] = (byte) v;
					buf[j + 1] = (byte) (v >> 8);
				}
			}
			phase += stepPerFrame;
			if (phase >= 1)
			{
				phase -= 1;
				clickPos = 0;
			}
		}
		lastClickPhase = phase;
	}

	static double frac(double x)
	{
		return x - Math.floor(x);
	}

	/** Wrap a phase difference into -0.5..0.5 ticks. */
	static double wrap(double x)
	{
		return x - Math.floor(x + 0.5);
	}

	// ---------------------------------------------------------------- decoding

	/** A song fully decoded to 16-bit PCM in memory. */
	static final class Decoded
	{
		final short[] pcm;
		final int channels;
		final float sampleRate;
		final long frames;

		Decoded(short[] pcm, int channels, float sampleRate)
		{
			this.pcm = pcm;
			this.channels = channels;
			this.sampleRate = sampleRate;
			this.frames = pcm.length / channels;
		}

		static Decoded load(File file) throws Exception
		{
			try (AudioInputStream raw = AudioSystem.getAudioInputStream(file))
			{
				AudioFormat src = raw.getFormat();
				int ch = src.getChannels();
				if (ch < 1 || ch > 2)
				{
					throw new IllegalArgumentException("only mono or stereo files are supported");
				}
				AudioFormat target = new AudioFormat(src.getSampleRate(), 16, ch, true, false);
				try (AudioInputStream in = AudioSystem.getAudioInputStream(target, raw))
				{
					byte[] bytes = readAll(in);
					short[] pcm = new short[bytes.length / 2];
					for (int i = 0; i < pcm.length; i++)
					{
						pcm[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
					}
					return new Decoded(pcm, ch, src.getSampleRate());
				}
			}
		}

		private static byte[] readAll(InputStream in) throws Exception
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
			byte[] tmp = new byte[1 << 16];
			int n;
			while ((n = in.read(tmp)) > 0)
			{
				bos.write(tmp, 0, n);
			}
			return bos.toByteArray();
		}
	}
}
