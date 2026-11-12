package com.tickbeat;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Run this main() in IntelliJ to open a dev RuneLite client with Tick Beat loaded. */
public class TickBeatPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TickBeatPlugin.class);
		RuneLite.main(args);
	}
}
