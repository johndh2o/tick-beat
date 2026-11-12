package com.tickbeat;

public enum MetronomeSound
{
	/** No metronome from this plugin. */
	OFF("Off"),
	/** The game's own sound effect (the one RuneLite's Metronome plugin uses), played on each tick. */
	GAME("Game sound"),
	/** A click mixed into the music stream: always exactly on the music's beat. */
	SYNCED_CLICK("Synced click");

	private final String label;

	MetronomeSound(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
