package com.tickbeat;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Where rendered audio goes. Abstracted so the sync engine can be tested without a sound card. */
interface AudioOut
{
	void open(float sampleRate, int channels) throws Exception;

	/** Blocks until the bytes are queued. */
	void write(byte[] data, int length);

	/** Frames actually played out of the speakers so far (since open). */
	long framesPlayed();

	void close();

	/** Real output through Java Sound. */
	final class JavaSound implements AudioOut
	{
		/** ~45 ms of buffer at 44.1 kHz: low latency, still safe against hiccups. */
		private static final int BUFFER_FRAMES = 2048;

		private SourceDataLine line;

		@Override
		public void open(float sampleRate, int channels) throws LineUnavailableException
		{
			close();
			AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
			line = AudioSystem.getSourceDataLine(fmt);
			line.open(fmt, BUFFER_FRAMES * fmt.getFrameSize());
			line.start();
		}

		@Override
		public void write(byte[] data, int length)
		{
			if (line != null)
			{
				line.write(data, 0, length);
			}
		}

		@Override
		public long framesPlayed()
		{
			return line == null ? 0 : line.getLongFramePosition();
		}

		@Override
		public void close()
		{
			if (line != null)
			{
				line.stop();
				line.flush();
				line.close();
				line = null;
			}
		}
	}
}
