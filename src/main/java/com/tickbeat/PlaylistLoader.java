package com.tickbeat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reads the music folder.
 *
 * playlist.txt, one song per line:
 *     file name | BPM | first downbeat in ms
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
			+ "# One song per line:   file name | BPM | first downbeat (ms)\n"
			+ "#\n"
			+ "# BPM: 100 locks one beat to every tick. 50 and 200 also work.\n"
			+ "#      Songs a few BPM off (98, 102...) are sped up or slowed down to fit.\n"
			+ "# First downbeat: how far into the file the first beat hits, in milliseconds.\n"
			+ "#      Find it in your DAW or audio editor. Get this right or the song will be in sync but off the beat.\n"
			+ "#\n"
			+ "# Files: .wav or .aiff (convert MP3s first).\n"
			+ "#\n"
			+ "# Example:\n"
			+ "# my song.wav | 100 | 412\n";

	private PlaylistLoader()
	{
	}

	/** Writes a song's BPM and beat position into playlist.txt (updates its line, or adds one). */
	static void save(File folder, Track track) throws IOException
	{
		File listFile = new File(folder, PLAYLIST);
		List<String> lines = listFile.exists()
			? new ArrayList<>(Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8))
			: new ArrayList<>(Arrays.asList(TEMPLATE.split("\n")));
		String newLine = String.format(Locale.ROOT, "%s | %.3f | %.0f", track.file.getName(), track.bpm, track.firstBeatMs);
		boolean found = false;
		for (int i = 0; i < lines.size(); i++)
		{
			String l = lines.get(i).trim();
			if (!l.startsWith("#") && l.contains("|") && l.split("\\|")[0].trim().equals(track.file.getName()))
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
		Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);
	}

	static List<Track> load(File folder, Consumer<String> messages)
	{
		List<Track> tracks = new ArrayList<>();
		if (!folder.isDirectory())
		{
			if (!folder.mkdirs())
			{
				messages.accept("Tick Beat: couldn't create music folder " + folder);
				return tracks;
			}
		}

		File listFile = new File(folder, PLAYLIST);
		Set<String> listed = new HashSet<>();
		if (!listFile.exists())
		{
			try
			{
				Files.write(listFile.toPath(), TEMPLATE.getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				messages.accept("Tick Beat: couldn't write " + listFile);
			}
		}
		else
		{
			try
			{
				int lineNo = 0;
				for (String line : Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8))
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
					File f = new File(folder, parts[0].trim());
					if (!f.isFile())
					{
						messages.accept("Tick Beat: playlist line " + lineNo + ": can't find " + f.getName());
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
						listed.add(f.getName());
					}
					catch (NumberFormatException e)
					{
						messages.accept("Tick Beat: playlist line " + lineNo + ": BPM and first beat must be numbers");
					}
				}
			}
			catch (IOException e)
			{
				messages.accept("Tick Beat: couldn't read " + listFile);
			}
		}

		// Files not mentioned in playlist.txt are only added automatically while the
		// playlist is still empty; once you (or the analyzer) list songs, the list rules.
		File[] files = listed.isEmpty() ? folder.listFiles() : null;
		if (files != null)
		{
			Arrays.sort(files);
			for (File f : files)
			{
				String n = f.getName().toLowerCase(Locale.ROOT);
				boolean audio = n.endsWith(".wav") || n.endsWith(".aif") || n.endsWith(".aiff");
				if (f.isFile() && audio && !listed.contains(f.getName()))
				{
					tracks.add(new Track(f, 100, 0));
				}
			}
		}
		return tracks;
	}
}
