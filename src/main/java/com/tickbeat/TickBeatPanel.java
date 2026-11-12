package com.tickbeat;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: live sliders for the music, the metronome and the sync offset.
 *
 * Dragging a slider changes the sound right away; the value is saved to the
 * plugin's settings when you let go.
 */
class TickBeatPanel extends PluginPanel
{
	private final TickBeatPlugin plugin;
	private final TickBeatConfig config;
	private final ConfigManager configManager;

	private final JLabel nowPlaying = new JLabel("Nothing playing");
	private final JLabel syncStatus = new JLabel(" ");
	private final JSlider musicVolume = new JSlider(0, 100);
	private final JSlider metronomeVolume = new JSlider(0, 100);
	private final JSlider offset = new JSlider(-300, 300);
	private final JComboBox<MetronomeSound> metronomeSound = new JComboBox<>(MetronomeSound.values());
	private final Timer statusTimer;

	private boolean updating;

	TickBeatPanel(TickBeatPlugin plugin, TickBeatConfig config, ConfigManager configManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel status = section();
		status.setLayout(new GridLayout(2, 1));
		status.add(nowPlaying);
		status.add(syncStatus);
		add(status);

		JPanel buttons = section();
		buttons.setLayout(new GridLayout(1, 3, 4, 0));
		JButton playPause = new JButton("Play/Stop");
		playPause.addActionListener(e -> save("playing", String.valueOf(!config.playing())));
		JButton next = new JButton("Next");
		next.addActionListener(e -> plugin.nextSong());
		JButton flip = new JButton("Flip beat");
		flip.setToolTipText("Song is on the upbeat? Move it half a beat so the tick lands on the beat. Saved for next time.");
		flip.addActionListener(e -> plugin.flipBeat());
		buttons.add(playPause);
		buttons.add(next);
		buttons.add(flip);
		add(buttons);

		add(sliderRow("Music volume", musicVolume, "%", "volume", plugin::previewMusicVolume));

		JPanel soundRow = section();
		soundRow.setLayout(new BorderLayout(0, 4));
		soundRow.add(new JLabel("Metronome"), BorderLayout.NORTH);
		soundRow.add(metronomeSound, BorderLayout.CENTER);
		metronomeSound.addActionListener(e ->
		{
			if (!updating)
			{
				save("metronomeSound", ((MetronomeSound) metronomeSound.getSelectedItem()).name());
			}
		});
		add(soundRow);

		add(sliderRow("Metronome volume", metronomeVolume, "%", "metronomeVolume", plugin::previewMetronomeVolume));
		add(sliderRow("Music offset", offset, " ms", "syncOffset", plugin::previewOffset));

		JLabel hint = new JLabel("<html>Turn on the Game sound metronome and drag <b>Music offset</b> "
			+ "until the beat and the tick sound land together. Positive values move the music later."
			+ "<br><br>If a song has the tick on the upbeat, press <b>Flip beat</b> while it plays."
			+ "<br><br>Songs go in <b>.runelite/plugin-data/tick-beat</b>.</html>");
		hint.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		add(hint);

		refreshFromConfig();

		statusTimer = new Timer(250, e -> refreshStatus());
		statusTimer.start();
	}

	void shutdown()
	{
		statusTimer.stop();
	}

	/** Pull current settings into the controls (called when settings change elsewhere). */
	void refreshFromConfig()
	{
		SwingUtilities.invokeLater(() ->
		{
			updating = true;
			musicVolume.setValue(config.volume());
			metronomeVolume.setValue(config.metronomeVolume());
			offset.setValue(config.syncOffset());
			metronomeSound.setSelectedItem(config.metronomeSound());
			updating = false;
		});
	}

	private void refreshStatus()
	{
		BeatSyncPlayer p = plugin.getPlayer();
		String song = p == null ? null : p.nowPlaying();
		if (plugin.isWaitingForLogin())
		{
			nowPlaying.setText("Starts when you log in");
			syncStatus.setText(" ");
		}
		else if (song == null || !config.playing())
		{
			nowPlaying.setText(config.playing() ? "No song loaded" : "Stopped");
			syncStatus.setText(" ");
		}
		else
		{
			nowPlaying.setText("♪ " + song);
			syncStatus.setText(String.format("Sync: %+.0f ms from tick", p.lastErrorMs()));
		}
	}

	private JPanel sliderRow(String title, JSlider slider, String unit, String key, IntConsumer preview)
	{
		JPanel row = section();
		row.setLayout(new BorderLayout(0, 2));
		JLabel label = new JLabel();
		row.add(label, BorderLayout.NORTH);
		row.add(slider, BorderLayout.CENTER);
		slider.setBackground(ColorScheme.DARK_GRAY_COLOR);
		slider.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 24));

		Runnable updateLabel = () -> label.setText(title + ": " + slider.getValue() + unit);
		updateLabel.run();
		slider.addChangeListener(e ->
		{
			updateLabel.run();
			if (updating)
			{
				return;
			}
			preview.accept(slider.getValue());
			if (!slider.getValueIsAdjusting())
			{
				save(key, String.valueOf(slider.getValue()));
			}
		});
		return row;
	}

	private JPanel section()
	{
		JPanel p = new JPanel();
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private void save(String key, String value)
	{
		configManager.setConfiguration(TickBeatConfig.GROUP, key, value);
	}
}
