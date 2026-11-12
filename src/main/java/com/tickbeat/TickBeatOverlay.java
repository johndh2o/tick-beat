package com.tickbeat;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

class TickBeatOverlay extends OverlayPanel
{
	private final TickBeatPlugin plugin;
	private final TickBeatConfig config;

	@Inject
	TickBeatOverlay(TickBeatPlugin plugin, TickBeatConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		BeatSyncPlayer player = plugin.getPlayer();
		if (!config.showOverlay() || player == null || !config.playing())
		{
			return null;
		}
		String song = player.nowPlaying();
		if (song == null)
		{
			return null;
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("♪ " + song)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Sync")
			.right(String.format("%+.0f ms", player.lastErrorMs()))
			.build());
		return super.render(graphics);
	}
}
