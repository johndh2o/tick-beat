package com.tickbeat;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(TickBeatConfig.GROUP)
public interface TickBeatConfig extends Config
{
	String GROUP = "tickbeat";

	@ConfigItem(
		keyName = "playing",
		name = "Play music",
		description = "Play the playlist, locked to the game tick",
		position = 0
	)
	default boolean playing()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onlyWhenLoggedIn",
		name = "Only play in game",
		description = "Wait until you're logged in before starting the music, and stop at the login screen",
		position = 0
	)
	default boolean onlyWhenLoggedIn()
	{
		return true;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "volume",
		name = "Volume",
		description = "Music volume",
		position = 1
	)
	default int volume()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "metronomeSound",
		name = "Metronome",
		description = "Game sound: the game's own tick sound, like RuneLite's Metronome plugin. Synced click: a click built into the music so it can never drift from the beat.",
		position = 2
	)
	default MetronomeSound metronomeSound()
	{
		return MetronomeSound.GAME;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "metronomeVolume",
		name = "Metronome volume",
		description = "Volume of the metronome",
		position = 3
	)
	default int metronomeVolume()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "shuffle",
		name = "Shuffle",
		description = "Shuffle the playlist",
		position = 5
	)
	default boolean shuffle()
	{
		return false;
	}

	@ConfigItem(
		keyName = "nextTrack",
		name = "Next song hotkey",
		description = "Skip to the next song",
		position = 6
	)
	default Keybind nextTrack()
	{
		return Keybind.NOT_SET;
	}

	@Range(min = -300, max = 300)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "syncOffset",
		name = "Music offset",
		description = "Move the music earlier (negative) or later (positive) so its beat lines up with the game tick sound. Also makes up for Bluetooth or slow audio devices.",
		position = 4
	)
	default int syncOffset()
	{
		return 0;
	}

	@Range(max = 10)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "maxStretch",
		name = "Max tempo change",
		description = "Songs that would need a bigger speed change than this to hit 100 BPM are skipped. Bigger changes are also audible as a pitch shift.",
		position = 7
	)
	default int maxStretch()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show now playing",
		description = "Show the current song and sync status on screen",
		position = 8
	)
	default boolean showOverlay()
	{
		return true;
	}
}
