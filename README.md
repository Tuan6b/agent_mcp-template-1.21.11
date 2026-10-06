# Agent MCP

A client-side Fabric mod for Minecraft 1.21.11 that embeds an [MCP](https://modelcontextprotocol.io) server inside the game.
Coding agents and AI models connect to it to drive the client: move, look, click menus, press keys, run commands,
take screenshots, read logs, and switch Iris shader packs. It's meant for automated testing of shaders and mods.

- Endpoint: `http://127.0.0.1:25599/mcp` (Streamable HTTP, JSON responses). It binds to loopback only.
- Change the port with the JVM argument `-Dagent_mcp.port=<port>`.
- The mod sets `pauseOnLostFocus` to false at startup, so the game keeps running while the agent's window has focus.
  Minecraft writes this value to `options.txt` the next time it saves settings, so the change persists.

## Connect an agent

Start Minecraft with the mod installed. The server comes up once the title screen loads.

**Claude Code**

```bash
claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp
```

**JSON config** (Cursor, VS Code, and other clients that support HTTP servers)

```json
{ "mcpServers": { "minecraft": { "type": "http", "url": "http://127.0.0.1:25599/mcp" } } }
```

**stdio-only clients** (for example Claude Desktop) can connect through the `mcp-remote` bridge:

```json
{ "mcpServers": { "minecraft": { "command": "npx", "args": ["-y", "mcp-remote", "http://127.0.0.1:25599/mcp"] } } }
```

## Tools

| Tool | What it does |
|---|---|
| `get_state` | FPS, window and GUI scale, open screen with its widgets (index, kind, label, rect), player position, rotation, health, dimension, biome, time, weather, looked-at block or entity, inventory, nearby entities, recent chat |
| `screenshot` | PNG of the rendered frame (shaders included). Options: `hide_hud`, `downscale`, `file`. Every shot is also saved to disk |
| `press_keys` | Hold key mappings by name (`forward`, `jump`, `attack`, `use`, `hotbar.3`, `iris.keybind.reload`, ...) for N ticks. Works whatever the binding and even when the window is unfocused |
| `key` | Physical key presses through Minecraft's real key handler, including chords: `["escape"]`, `["f1"]`, `["f3","g"]`, `["left.control","a"]` |
| `type_text` | Type into the focused text field |
| `mouse` | Click, double-click, drag, scroll, or move on the open screen, targeting `label`, widget index, or x/y coordinates |
| `look` | Set the camera with absolute yaw/pitch, relative deltas, or `at: [x,y,z]` |
| `chat` | Send a chat message or `/command`. Returns the chat lines that follow |
| `read_log` | Tail `logs/latest.log` with an optional regex (useful for shader compile errors and mod exceptions) |
| `world` | List, open, or leave singleplayer worlds, or connect to a server |
| `shader` | Iris: status, list, reload, enable, disable, set_pack |
| `reload_resources` | Reload resource packs (F3+T) and wait until it finishes |
| `set_option` | FOV, render and simulation distance, GUI scale, gamma, max FPS, HUD visibility, window size |
| `list_keybinds` | Every key mapping name (vanilla and modded) with its current binding |
| `wait` | Wait a number of client ticks or rendered frames |

A typical shader test loop:

1. `world open` a test world.
2. `chat "/time set sunset"`, then `chat "/tp @s 0 120 0"`.
3. `look` to frame the shot.
4. `shader set_pack` (or `reload` after editing files), then `wait 40` ticks.
5. `screenshot hide_hud:true`.
6. `read_log grep:"error|shader"`.

## Development

Loom 1.18 needs **JDK 25** to run Gradle (the mod itself targets Java 21):

```bash
JAVA_HOME="/path/to/jdk-25" ./gradlew runClient
```

`runClient` loads Sodium and Iris (dev only, never bundled into the jar), so you can test shader packs from `run/shaderpacks/`.
`run/` is gitignored, so a fresh clone starts with no packs; drop one in before testing.
To build the jar, run `./gradlew build`; the output goes to `build/libs/`.

Layout (all in `src/client`):

- `McpServer`: the JSON-RPC server over HTTP.
- `Tools`: the tool definitions.
- `Agent_MCPClient`: startup, held-key ticking, and the frame counter.
- `mixin/`: the frame counter hook, the mining-while-unfocused fix, and accessors for key mappings, the keyboard handler, and chat.

## License

CC0-1.0
