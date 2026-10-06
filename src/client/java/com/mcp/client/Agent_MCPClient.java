package com.mcp.client;

import com.mcp.Agent_MCP;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Agent_MCPClient implements ClientModInitializer {
	public static final int DEFAULT_PORT = 25599;
	/** Key mappings the agent is holding -> client ticks left. */
	public static final Map<KeyMapping, Integer> HELD = new ConcurrentHashMap<>();
	public static volatile long frames;
	public static volatile long ticks;

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			// The agent drives the game while another window has focus; don't open the pause menu on it.
			mc.options.pauseOnLostFocus = false;
			int port = Integer.getInteger("agent_mcp.port", DEFAULT_PORT);
			try {
				McpServer.start(port);
			} catch (IOException e) {
				Agent_MCP.LOGGER.error("Could not start MCP server on port {} (set -Dagent_mcp.port=<port>)", port, e);
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> McpServer.stop());

		// ponytail: holds are re-asserted every tick because KeyMapping.setAll() resets them from the
		// physical keyboard whenever a screen opens/closes; a mixin on setAll would be the upgrade.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			ticks++;
			HELD.forEach((key, left) -> {
				if (left <= 0) {
					key.setDown(false);
					HELD.remove(key);
				} else {
					key.setDown(true);
					HELD.put(key, left - 1);
				}
			});
		});
	}
}
