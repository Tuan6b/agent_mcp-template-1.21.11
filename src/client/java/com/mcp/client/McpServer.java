package com.mcp.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcp.Agent_MCP;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server over the Streamable HTTP transport (JSON responses only, no SSE, no sessions).
 * Endpoint: POST http://127.0.0.1:&lt;port&gt;/mcp
 */
public final class McpServer {
	private static final Set<String> PROTOCOL_VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
	private static final String LATEST_PROTOCOL = "2025-06-18";
	private static HttpServer server;

	static void start(int port) throws IOException {
		// Loopback only: anything that can reach this port can drive the game.
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/mcp", McpServer::handle);
		server.setExecutor(Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r, "agent-mcp-http");
			t.setDaemon(true);
			return t;
		}));
		server.start();
		Agent_MCP.LOGGER.info("MCP server listening on http://127.0.0.1:{}/mcp", port);
	}

	static void stop() {
		if (server != null) server.stop(0);
	}

	private static void handle(HttpExchange ex) throws IOException {
		try (ex) {
			String origin = ex.getRequestHeaders().getFirst("Origin");
			if (origin != null && !isLocalOrigin(origin)) { // DNS-rebinding protection required by the MCP spec
				send(ex, 403, null);
				return;
			}
			if (!ex.getRequestMethod().equals("POST")) { // no server->client SSE stream, no sessions to DELETE
				ex.getResponseHeaders().add("Allow", "POST");
				send(ex, 405, null);
				return;
			}
			String version = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
			if (version != null && !PROTOCOL_VERSIONS.contains(version)) {
				send(ex, 400, error(JsonNull.INSTANCE, -32600, "Unsupported MCP-Protocol-Version: " + version));
				return;
			}
			JsonObject msg;
			try {
				msg = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (Exception e) {
				send(ex, 400, error(JsonNull.INSTANCE, -32700, "Parse error"));
				return;
			}
			if (!msg.has("method") || !msg.has("id")) { // notification or response: nothing to answer
				send(ex, 202, null);
				return;
			}
			send(ex, 200, dispatch(msg));
		}
	}

	static JsonObject dispatch(JsonObject msg) {
		JsonElement id = msg.get("id");
		JsonObject params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();
		try {
			JsonObject result = switch (msg.get("method").getAsString()) {
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				case "tools/list" -> {
					JsonObject r = new JsonObject();
					r.add("tools", Tools.list());
					yield r;
				}
				case "tools/call" -> Tools.call(params.get("name").getAsString(),
						params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject());
				default -> null;
			};
			if (result == null) return error(id, -32601, "Method not found: " + msg.get("method").getAsString());
			JsonObject res = new JsonObject();
			res.addProperty("jsonrpc", "2.0");
			res.add("id", id);
			res.add("result", result);
			return res;
		} catch (IllegalArgumentException e) {
			return error(id, -32602, e.getMessage());
		} catch (Exception e) {
			Agent_MCP.LOGGER.error("MCP request failed", e);
			return error(id, -32603, String.valueOf(e));
		}
	}

	private static JsonObject initialize(JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : LATEST_PROTOCOL;
		JsonObject r = new JsonObject();
		r.addProperty("protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL);
		JsonObject caps = new JsonObject();
		caps.add("tools", new JsonObject());
		r.add("capabilities", caps);
		JsonObject info = new JsonObject();
		info.addProperty("name", "minecraft-agent-mcp");
		info.addProperty("version", "1.0.0");
		r.add("serverInfo", info);
		r.addProperty("instructions", """
				Controls a running Minecraft (Fabric 1.21.11) client for testing shaders and mods.
				Typical loop: get_state -> act (press_keys / look / mouse / key / chat) -> screenshot to verify.
				Menus: mouse with a widget label or index from get_state (GUI-scaled coords). In game: press_keys + look.
				Stage scenes with chat commands ('/time set', '/weather', '/tp', '/gamemode'); check read_log for shader/mod errors.""");
		return r;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		JsonObject res = new JsonObject();
		res.addProperty("jsonrpc", "2.0");
		res.add("id", id);
		res.add("error", err);
		return res;
	}

	private static boolean isLocalOrigin(String origin) {
		try {
			String host = URI.create(origin).getHost();
			return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void send(HttpExchange ex, int status, JsonObject body) throws IOException {
		if (body == null) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
	}
}
