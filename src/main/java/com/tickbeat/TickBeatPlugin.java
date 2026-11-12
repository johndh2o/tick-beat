package com.tickbeat;

import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

@Slf4j
@PluginDescriptor(
	name = "Tick Beat",
	description = "Plays your own 100 BPM playlist with every beat locked to the game tick",
	tags = {"music", "metronome", "tick", "bpm", "rhythm", "playlist"}
)
public class TickBeatPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TickBeatConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TickBeatOverlay overlay;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	/** The game's tick sound effect, the same one RuneLite's Metronome plugin plays. */
	private static final int GAME_TICK_SOUND = 3929; // SoundEffectID.GE_INCREMENT_PLOP

	private TickBeatPanel panel;
	private NavigationButton navButton;
	private volatile int previewMetronomeVolume = -1;
	private volatile boolean inGame;

	@Getter
	private BeatSyncPlayer player;

	private final TickClock tickClock = new TickClock();

	private final HotkeyListener nextListener = new HotkeyListener(() -> config.nextTrack())
	{
		@Override
		public void hotkeyPressed()
		{
			if (player != null)
			{
				player.next();
			}
		}
	};

	@Override
	protected void startUp()
	{
		inGame = isInGame(client.getGameState());
		player = new BeatSyncPlayer(new AudioOut.JavaSound(), System::nanoTime, tickClock, this::message);
		applySettings();
		reloadPlaylist();
		player.start();
		keyManager.registerKeyListener(nextListener);
		overlayManager.add(overlay);

		panel = new TickBeatPanel(this, config, configManager);
		navButton = NavigationButton.builder()
			.tooltip("Tick Beat")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel.shutdown();
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(nextListener);
		if (player != null)
		{
			player.shutdown();
			player = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Timestamp as early as possible; everything else happens on the audio thread.
		tickClock.onTick(System.nanoTime());

		if (config.metronomeSound() == MetronomeSound.GAME && config.playing() && inGame)
		{
			int vol = previewMetronomeVolume >= 0 ? previewMetronomeVolume : config.metronomeVolume();
			if (vol > 0)
			{
				// The game's sound volume runs 0-127.
				client.playSoundEffect(GAME_TICK_SOUND, vol * 127 / 100);
			}
		}
	}

	// Live previews from the side panel's sliders (applied while dragging, saved on release).

	void previewMusicVolume(int v)
	{
		if (player != null)
		{
			player.setVolume(v);
		}
	}

	void previewMetronomeVolume(int v)
	{
		previewMetronomeVolume = v;
		if (player != null && config.metronomeSound() == MetronomeSound.SYNCED_CLICK)
		{
			player.setClickVolume(v);
		}
	}

	void previewOffset(int ms)
	{
		if (player != null)
		{
			player.setOffsetMs(ms);
		}
	}

	/**
	 * The song is locked to the tick but on the off-beat: move it half a tick
	 * and save that to playlist.txt so it's right next time too.
	 */
	void flipBeat()
	{
		Track t = player == null ? null : player.currentTrack();
		if (t == null)
		{
			return;
		}
		double len = t.tickLengthMs();
		t.firstBeatMs = (t.firstBeatMs + len / 2) % len;
		player.beatPositionChanged();
		try
		{
			PlaylistLoader.save(musicFolder(), t);
		}
		catch (java.io.IOException e)
		{
			message("Tick Beat: couldn't save the new beat position to playlist.txt");
		}
	}

	void nextSong()
	{
		if (player != null)
		{
			player.next();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		boolean now = isInGame(event.getGameState());
		if (now != inGame)
		{
			inGame = now;
			if (player != null)
			{
				applySettings();
			}
		}
	}

	/** Logged in, or briefly loading / hopping worlds: keep the music going through those. */
	private static boolean isInGame(GameState state)
	{
		return state == GameState.LOGGED_IN
			|| state == GameState.LOADING
			|| state == GameState.HOPPING
			|| state == GameState.CONNECTION_LOST;
	}

	/** True when the music is switched on but waiting for you to log in. */
	boolean isWaitingForLogin()
	{
		return config.playing() && config.onlyWhenLoggedIn() && !inGame;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TickBeatConfig.GROUP.equals(event.getGroup()) || player == null)
		{
			return;
		}
		previewMetronomeVolume = -1;
		applySettings();
		if (panel != null)
		{
			panel.refreshFromConfig();
		}
		String key = event.getKey();
		if ("musicFolder".equals(key) || "shuffle".equals(key))
		{
			reloadPlaylist();
		}
		if ("playing".equals(key) && config.playing())
		{
			// Pick up songs added to the folder since last time.
			reloadPlaylist();
		}
	}

	@Provides
	TickBeatConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TickBeatConfig.class);
	}

	private void applySettings()
	{
		player.setVolume(config.volume());
		player.setOffsetMs(config.syncOffset());
		player.setMaxStretchPercent(config.maxStretch());
		player.setPlaying(config.playing() && (inGame || !config.onlyWhenLoggedIn()));
		player.setClickVolume(config.metronomeSound() == MetronomeSound.SYNCED_CLICK ? config.metronomeVolume() : 0);
	}

	private void reloadPlaylist()
	{
		File folder = musicFolder();
		java.util.List<Track> tracks = PlaylistLoader.load(folder, this::message);
		if (tracks.isEmpty())
		{
			message("Tick Beat: no songs yet. Put .wav files in " + folder.getAbsolutePath()
				+ " and set their BPM in " + PlaylistLoader.PLAYLIST + ".");
		}
		player.setPlaylist(tracks, config.shuffle());
	}

	private File musicFolder()
	{
		String custom = config.musicFolder().trim();
		return custom.isEmpty() ? new File(RuneLite.RUNELITE_DIR, "tick-beat") : new File(custom);
	}

	/** A small music-note icon for the sidebar, drawn in code so no image file is needed. */
	private static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xFF, 0x98, 0x1F));
		g.fillOval(1, 10, 6, 5);
		g.fillOval(9, 8, 6, 5);
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(6, 12, 6, 2);
		g.drawLine(14, 10, 14, 1);
		g.setStroke(new BasicStroke(2.4f));
		g.drawLine(6, 2, 14, 1);
		g.dispose();
		return img;
	}

	/** Shows a message in the game chat (or the log if not logged in). Safe from any thread. */
	private void message(String text)
	{
		log.info(text);
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == net.runelite.api.GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
			}
		});
	}
}
