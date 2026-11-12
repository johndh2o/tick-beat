package com.tickbeat;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * Reads the music folder (.runelite/plugin-data/tick-beat).
 *
 * playlist.txt, one song per line:
 *     file name | BPM | first beat in ms
 * e.g.
 *     my song.wav | 100 | 412
 *
 * While playlist.txt lists no songs, every .wav/.aiff in the folder plays at 100 BPM, first beat 0 ms.
 * Once it lists songs, only those play.
 */
final class PlaylistLoader
{
	static final String PLAYLIST = "playlist.txt";

	private static final String TEMPLATE =
		"# Tick Beat playlist\n"
			+ "# One song per line:   file name | BPM | first beat (ms)\n"
			+ "#\n"
			+ "# BPM: 100 locks one beat to every tick. 50 and 200 also work.\n"
			+ "#      Songs a few BPM off (98, 102...) are sped up or slowed down to fit.\n"
			+ "# First beat: how far into the file a beat hits, in milliseconds.\n"
			+ "#      Get this right or the song will be in sync but off the beat.\n"
			+ "#\n"
			+ "# Files: .wav or .aiff (convert MP3s first).\n"
			+ "#\n"
			+ "# Example:\n"
			+ "# my song.wav | 100 | 412\n";

	private PlaylistLoader()
	{
	}

	private static List<String> readLines(Filepath file) throws IOException
	{
		try (BufferedReader r = file.openBufferedReader())
		{
			return r.lines().collect(Collectors.toList());
		}
	}

	/** Writes a song's BPM and beat position into playlist.txt (updates its line, or adds one). */
	static void save(Filepath folder, Track track) throws IOException
	{
		Filepath listFile = folder.joinSegment(PLAYLIST);
		List<String> lines = listFile.exists()
			? new ArrayList<>(readLines(listFile))
			: new ArrayList<>(Arrays.asList(TEMPLATE.split("\n")));
		String newLine = String.format(Locale.ROOT, "%s | %.3f | %.0f", track.fileName(), track.bpm, track.firstBeatMs);
		boolean found = false;
		for (int i = 0; i < lines.size(); i++)
		{
			String l = lines.get(i).trim();
			if (!l.startsWith("#") && l.contains("|") && l.split("\\|")[0].trim().equals(track.fileName()))
			{
				lines.set(i, newLine);
				found = true;
				break;
			}
		}
		if (!found)
		{
			lines.add(newLine);
		}
		listFile.write(String.join("\n", lines) + "\n");
	}

	static List<Track> load(Filepath folder, Consumer<String> messages)
	{
		List<Track> tracks = new ArrayList<>();
		try
		{
			folder.createDirectories();
		}
		catch (IOException e)
		{
			messages.accept("Tick Beat: couldn't create music folder " + folder);
			return tracks;
		}

		Filepath listFile = folder.joinSegment(PLAYLIST);
		Set<String> listed = new HashSet<>();
		if (!listFile.exists())
		{
			try
			{
				listFile.write(TEMPLATE);
			}
			catch (IOException e)
			{
				messages.accept("Tick Beat: couldn't write " + PLAYLIST);
			}
		}
		else
		{
			try
			{
				int lineNo = 0;
				for (String line : readLines(listFile))
				{
					lineNo++;
					line = line.trim();
					if (line.startsWith("#") && line.contains("|"))
					{
						// A song the analyzer left out on purpose ("# too far from 100 BPM ...: song.wav | ...").
						String head = line.substring(0, line.indexOf('|'));
						int colon = head.lastIndexOf(": ");
						if (colon >= 0)
						{
							listed.add(head.substring(colon + 2).trim());
						}
						continue;
					}
					if (line.isEmpty() || line.startsWith("#"))
					{
						continue;
					}
					String[] parts = line.split("\\|");
					String name = parts[0].trim();
					Filepath f;
					try
					{
						f = folder.joinSegment(name);
					}
					catch (IllegalArgumentException e)
					{
						messages.accept("Tick Beat: playlist line " + lineNo + ": invalid file name " + name);
						continue;
					}
					if (!f.isFile())
					{
						messages.accept("Tick Beat: playlist line " + lineNo + ": can't find " + name);
						continue;
					}
					try
					{
						double bpm = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 100;
						double first = parts.length > 2 ? Double.parseDouble(parts[2].trim()) : 0;
						if (bpm <= 0)
						{
							throw new NumberFormatException();
						}
						tracks.add(new Track(f, bpm, first));
						listed.add(name);
					}
					catch (NumberFormatException e)
					{
						messages.accept("Tick Beat: playlist line " + lineNo + ": BPM and first beat must be numbers");
					}
				}
			}
			catch (IOException e)
			{
				messages.accept("Tick Beat: couldn't read " + PLAYLIST);
			}
		}

		// Files not mentioned in playlist.txt are only added automatically while the
		// playlist is still empty; once you (or the analyzer) list songs, the list rules.
		if (listed.isEmpty())
		{
			try (Stream<Filepath> files = folder.walk(1))
			{
				files
					.filter(Filepath::isFile)
					.filter(f ->
					{
						String n = f.getFileName().toLowerCase(Locale.ROOT);
						return n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff");
					})
					.sorted(Comparator.comparing(Filepath::getFileName))
					.forEach(f -> tracks.add(new Track(f, 100, 0)));
			}
			catch (IOException e)
			{
				messages.accept("Tick Beat: couldn't list the music folder");
			}
		}
		return tracks;
	}
}
