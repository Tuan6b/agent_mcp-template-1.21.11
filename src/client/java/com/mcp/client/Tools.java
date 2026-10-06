package com.mcp.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcp.client.mixin.ChatComponentAccessor;
import com.mcp.client.mixin.KeyMappingAccessor;
import com.mcp.client.mixin.KeyboardHandlerInvoker;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/** MCP tool definitions. Handlers run on HTTP threads and hop onto the client thread via {@link #onClient}. */
public final class Tools {
	@FunctionalInterface
	private interface Handler {
		JsonArray call(JsonObject args) throws Exception;
	}

	private record Tool(JsonObject definition, Handler handler) {}

	private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();
	private static final int TIMEOUT_S = 15;
	private static final int MAX_WIDGETS = 200;

	static {
		register("get_state", """
				Snapshot of the client: fps, window and GUI size, the open screen with its widgets (index i, kind, label, \
				GUI-scaled rect [x,y,w,h]), and when in a world: position, rotation, health, gamemode, dimension, biome, \
				time, weather, looked-at block/entity, inventory, nearby entities, recent chat.""",
				"{}", a -> {
					awaitFrames(1); // list widgets only get positioned when first rendered
					return text(onClient(Tools::state));
				});

		register("screenshot", """
				Capture the rendered frame (including shaders) as PNG. Waits for fresh frames first so it reflects your \
				last action. Also saved to disk; the path is returned.""", """
				{"downscale": {"type": "integer", "minimum": 1, "description": "Integer shrink factor. Default keeps width <= 1280px."},
				 "hide_hud": {"type": "boolean", "description": "Hide HUD and hand for this shot (like F1)."},
				 "file": {"type": "string", "description": "File name (e.g. sunset_v2.png) inside <game dir>/screenshots/agent_mcp/. Default <millis>.png"}}""",
				Tools::screenshot);

		register("press_keys", """
				Press and hold game key mappings together for some ticks, independent of how they are bound and of \
				window focus. Use this for movement and in-world actions. Returns after release.""", """
				{"keys": {"type": "array", "items": {"type": "string"}, "description": "Mapping names: forward, back, left, right, jump, sneak, sprint, attack, use, drop, inventory, swapOffhand, pickItem, togglePerspective, chat, command, hotbar.1..hotbar.9, or any full name from list_keybinds (e.g. iris.keybind.reload)."},
				 "ticks": {"type": "integer", "minimum": 1, "description": "Hold duration in client ticks (20 = 1 second). Default 1 = tap."}}""",
				Tools::pressKeys, "keys");

		register("key", """
				Simulate physical keyboard keys through Minecraft's real key handler: works in GUIs (escape, enter, tab, \
				arrows, backspace) and in game (f1 hide HUD, f2 screenshot, f3 debug, f5 perspective). Several keys form a \
				chord: pressed in order, released in reverse, e.g. ["f3","t"] reloads resource packs, ["left.control","a"].""", """
				{"keys": {"type": "array", "items": {"type": "string"}, "description": "Key names like escape, enter, tab, backspace, delete, up, down, left, right, space, a..z, 0..9, f1..f12, left.shift, left.control, left.alt, or full key.keyboard.* names."},
				 "ticks": {"type": "integer", "minimum": 0, "description": "Client ticks to hold before releasing. Default 0."}}""",
				Tools::key, "keys");

		register("type_text", "Type text into the focused field of the open screen (chat, sign, search box, world name...).", """
				{"text": {"type": "string"}}""", Tools::typeText, "text");

		register("mouse", """
				Mouse input on the open screen (GUI-scaled coords from get_state). Target either x/y, a widget index, or a \
				widget label. For in-world clicking use press_keys attack/use.""", """
				{"action": {"type": "string", "enum": ["click", "double_click", "move", "drag", "scroll"], "description": "Default click."},
				 "x": {"type": "number"}, "y": {"type": "number"},
				 "widget": {"type": "integer", "description": "Widget index i from get_state; clicks its center."},
				 "label": {"type": "string", "description": "Click the first widget whose label contains this text (case-insensitive)."},
				 "button": {"type": "string", "enum": ["left", "right", "middle"], "description": "Default left."},
				 "to_x": {"type": "number", "description": "drag end x"}, "to_y": {"type": "number", "description": "drag end y"},
				 "amount": {"type": "number", "description": "scroll amount, positive = up. Default -1."}}""",
				Tools::mouse);

		register("look", """
				Set the camera. Absolute yaw/pitch (yaw 0=south, 90=west, 180=north, -90=east; pitch -90=up, 90=down), \
				relative dyaw/dpitch, or look at a world position.""", """
				{"yaw": {"type": "number"}, "pitch": {"type": "number"},
				 "dyaw": {"type": "number"}, "dpitch": {"type": "number"},
				 "at": {"type": "array", "items": {"type": "number"}, "minItems": 3, "maxItems": 3, "description": "[x, y, z] to look at"}}""",
				Tools::look);

		register("chat", """
				Send a chat message, or a command if it starts with '/' (e.g. /time set midnight, /weather rain, \
				/tp @s 0 100 0, /gamemode creative, /give @s diamond 4). Returns chat lines received afterwards.""", """
				{"message": {"type": "string"},
				 "wait_ticks": {"type": "integer", "minimum": 0, "description": "How long to collect replies. Default 10."}}""",
				Tools::chat, "message");

		register("read_log", "Tail the game log (logs/latest.log): shader compile errors, mod exceptions, warnings.", """
				{"lines": {"type": "integer", "minimum": 1, "description": "Default 80."},
				 "grep": {"type": "string", "description": "Case-insensitive regex filter, e.g. 'error|exception|shader'."}}""",
				Tools::readLog);

		register("world", """
				Singleplayer worlds and servers. list: saved worlds; open: load a world by folder name; leave: save and \
				quit to title; connect: join a server. Loading is async: poll get_state until in_world is true and no screen is open.""", """
				{"action": {"type": "string", "enum": ["list", "open", "leave", "connect"]},
				 "name": {"type": "string", "description": "World folder name for open."},
				 "address": {"type": "string", "description": "host[:port] for connect."}}""",
				Tools::world, "action");

		register("shader", """
				Control Iris shaders (requires Iris). status, list packs, reload (after editing shader files), enable, \
				disable, set_pack. Check read_log afterwards for compile errors, and wait ~40 ticks before a screenshot \
				so chunks finish rebuilding.""", """
				{"action": {"type": "string", "enum": ["status", "list", "reload", "enable", "disable", "set_pack"]},
				 "pack": {"type": "string", "description": "Shader pack file/folder name for set_pack."}}""",
				Tools::shader, "action");

		register("reload_resources", "Reload all resource packs (like F3+T) and wait until done.", "{}", a -> {
			onClient(() -> Minecraft.getInstance().reloadResourcePacks()).get(5, TimeUnit.MINUTES);
			return text("Resource packs reloaded.");
		});

		register("set_option", "Change video/game options. Only given fields change; returns the resulting values.", """
				{"fov": {"type": "integer", "minimum": 30, "maximum": 110},
				 "render_distance": {"type": "integer", "minimum": 2, "maximum": 32},
				 "simulation_distance": {"type": "integer", "minimum": 5, "maximum": 32},
				 "gui_scale": {"type": "integer", "minimum": 0, "description": "0 = auto"},
				 "gamma": {"type": "number", "minimum": 0, "maximum": 1, "description": "Brightness"},
				 "max_fps": {"type": "integer", "minimum": 10, "maximum": 260, "description": "260 = unlimited"},
				 "hide_hud": {"type": "boolean"},
				 "window_width": {"type": "integer"}, "window_height": {"type": "integer"}}""",
				Tools::setOption);

		register("list_keybinds", "List key mapping names (vanilla and modded) with their bound keys, for press_keys.", """
				{"filter": {"type": "string", "description": "Substring filter on the name."}}""", a -> {
			String filter = str(a, "filter", "").toLowerCase(Locale.ROOT);
			return text(onClient(() -> String.join("\n", Arrays.stream(Minecraft.getInstance().options.keyMappings)
					.filter(k -> k.getName().toLowerCase(Locale.ROOT).contains(filter))
					.map(k -> k.getName() + " = " + k.getTranslatedKeyMessage().getString())
					.toList())));
		});

		register("wait", "Wait for client ticks (20/s) and/or rendered frames.", """
				{"ticks": {"type": "integer", "minimum": 0}, "frames": {"type": "integer", "minimum": 0}}""", a -> {
			awaitTicks(intArg(a, "ticks", 0));
			awaitFrames(intArg(a, "frames", 0));
			return text("ok");
		});
	}

	static JsonArray list() {
		JsonArray tools = new JsonArray();
		TOOLS.values().forEach(t -> tools.add(t.definition()));
		return tools;
	}

	static JsonObject call(String name, JsonObject args) {
		Tool tool = TOOLS.get(name);
		if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
		JsonObject result = new JsonObject();
		try {
			result.add("content", tool.handler().call(args));
		} catch (Exception e) {
			result.add("content", text(e.getClass().getSimpleName() + ": " + e.getMessage()));
			result.addProperty("isError", true);
		}
		return result;
	}

	// ---- tools ----

	private static JsonObject state() {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		JsonObject s = new JsonObject();
		s.addProperty("fps", mc.getFps());
		s.addProperty("window", window.getWidth() + "x" + window.getHeight());
		s.addProperty("gui_scale", window.getGuiScale());
		s.addProperty("gui_size", window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight());
		s.addProperty("paused", mc.isPaused());
		s.addProperty("window_focused", mc.isWindowActive());
		s.addProperty("in_world", mc.player != null && mc.level != null);
		if (mc.getOverlay() != null) s.addProperty("loading_overlay", true);
		if (!Agent_MCPClient.HELD.isEmpty()) s.addProperty("held_keys", Agent_MCPClient.HELD.keySet().stream().map(KeyMapping::getName).toList().toString());
		if (mc.screen != null) {
			JsonObject screen = new JsonObject();
			screen.addProperty("title", mc.screen.getTitle().getString());
			screen.addProperty("class", mc.screen.getClass().getName());
			JsonArray widgets = new JsonArray();
			List<GuiEventListener> list = widgets(mc.screen);
			for (int i = 0; i < list.size(); i++) widgets.add(widget(i, list.get(i)));
			screen.add("widgets", widgets);
			s.add("screen", screen);
		}

		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return s;
		s.addProperty("pos", String.format(Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ()));
		s.addProperty("yaw", Math.round(Mth.wrapDegrees(p.getYRot()) * 10) / 10.0);
		s.addProperty("pitch", Math.round(p.getXRot() * 10) / 10.0);
		s.addProperty("health", p.getHealth());
		s.addProperty("food", p.getFoodData().getFoodLevel());
		if (mc.gameMode != null) s.addProperty("gamemode", mc.gameMode.getPlayerMode().getName());
		s.addProperty("dimension", level.dimension().identifier().toString());
		s.addProperty("biome", level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse("unknown"));
		s.addProperty("time_of_day", level.getDayTime() % 24000);
		s.addProperty("weather", level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear");
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
			s.addProperty("looking_at", BuiltInRegistries.BLOCK.getKey(level.getBlockState(b.getBlockPos()).getBlock())
					+ " at " + b.getBlockPos().toShortString() + " face " + b.getDirection().getName());
		} else if (hit instanceof EntityHitResult e) {
			s.addProperty("looking_at", describe(e.getEntity(), p));
		}
		s.addProperty("selected_slot", p.getInventory().getSelectedSlot());
		JsonArray inventory = new JsonArray();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack stack = p.getInventory().getItem(i);
			if (!stack.isEmpty()) inventory.add(i + ": " + stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
		}
		s.add("inventory", inventory);
		JsonArray entities = new JsonArray();
		StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
				.filter(e -> e != p && e.distanceTo(p) < 32)
				.sorted(Comparator.comparingDouble(e -> e.distanceTo(p)))
				.limit(10)
				.forEach(e -> entities.add(describe(e, p)));
		s.add("nearby_entities", entities);
		List<GuiMessage> chat = chat(mc);
		JsonArray recent = new JsonArray();
		for (int i = Math.min(10, chat.size()) - 1; i >= 0; i--) recent.add(chat.get(i).content().getString());
		s.add("recent_chat", recent);
		return s;
	}

	private static JsonArray screenshot(JsonObject a) throws Exception {
		// Only bare names inside the screenshot folder: anything on loopback can call this, so no arbitrary file writes.
		String name = str(a, "file", "");
		if (name.isBlank()) name = System.currentTimeMillis() + ".png";
		if (!name.matches("[A-Za-z0-9._-]{1,64}\\.png")) throw new IllegalArgumentException("file must be a plain name like shot1.png");
		Path file = FabricLoader.getInstance().getGameDir().resolve("screenshots/agent_mcp").resolve(name).toAbsolutePath();

		Minecraft mc = Minecraft.getInstance();
		boolean hideHud = a.has("hide_hud") && a.get("hide_hud").getAsBoolean();
		boolean wasHidden = onClient(() -> {
			boolean prev = mc.options.hideGui;
			if (hideHud) mc.options.hideGui = true;
			return prev;
		});
		CompletableFuture<NativeImage> shot = new CompletableFuture<>();
		int factor;
		NativeImage image;
		try {
			awaitFrames(2); // the frame must be rendered after the agent's last action
			factor = onClient(() -> {
				RenderTarget target = mc.getMainRenderTarget();
				int f = downscale(target.width, target.height, intArg(a, "downscale", (target.width + 1279) / 1280));
				Screenshot.takeScreenshot(target, f, shot::complete); // GPU readback completes on a later frame
				return f;
			});
			image = shot.get(TIMEOUT_S, TimeUnit.SECONDS);
		} finally {
			if (hideHud) onClient(() -> mc.options.hideGui = wasHidden);
		}

		Files.createDirectories(file.getParent());
		int w, h;
		try (image) {
			w = image.getWidth();
			h = image.getHeight();
			image.writeToFile(file);
		}
		int guiScale = mc.getWindow().getGuiScale();
		JsonArray content = text(String.format(Locale.ROOT,
				"%dx%d PNG saved to %s. GUI coords = image pixels * %s (downscale %d / gui scale %d).",
				w, h, file, factor / (double) guiScale, factor, guiScale));
		JsonObject img = new JsonObject();
		img.addProperty("type", "image");
		img.addProperty("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
		img.addProperty("mimeType", "image/png");
		content.add(img);
		return content;
	}

	private static JsonArray pressKeys(JsonObject a) throws Exception {
		int ticks = Math.max(1, intArg(a, "ticks", 1));
		List<KeyMapping> keys = new ArrayList<>();
		for (JsonElement e : a.getAsJsonArray("keys")) keys.add(keyMapping(e.getAsString()));
		onClient(() -> {
			for (KeyMapping k : keys) {
				KeyMappingAccessor acc = (KeyMappingAccessor) k;
				acc.agent_mcp$setClickCount(acc.agent_mcp$getClickCount() + 1);
				k.setDown(true);
				Agent_MCPClient.HELD.put(k, ticks);
			}
			return null;
		});
		await(() -> keys.stream().noneMatch(Agent_MCPClient.HELD::containsKey), ticks * 50L + TIMEOUT_S * 1000L, "key release");
		return text("Held " + keys.stream().map(KeyMapping::getName).toList() + " for " + ticks + " ticks.");
	}

	private static JsonArray key(JsonObject a) throws Exception {
		List<Integer> codes = new ArrayList<>();
		int mods = 0;
		for (JsonElement e : a.getAsJsonArray("keys")) {
			String name = e.getAsString().toLowerCase(Locale.ROOT);
			InputConstants.Key k = InputConstants.getKey(name.startsWith("key.") ? name : "key.keyboard." + name);
			if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() < 0) throw new IllegalArgumentException("Not a keyboard key: " + name);
			codes.add(k.getValue());
			mods |= switch (k.getValue()) { // GLFW modifier bits for held modifier keys
				case 340, 344 -> 1;
				case 341, 345 -> 2;
				case 342, 346 -> 4;
				case 343, 347 -> 8;
				default -> 0;
			};
		}
		int modifiers = mods;
		Minecraft mc = Minecraft.getInstance();
		KeyboardHandlerInvoker keyboard = (KeyboardHandlerInvoker) mc.keyboardHandler;
		onClient(() -> {
			for (int code : codes) keyboard.agent_mcp$keyPress(mc.getWindow().handle(), 1, new KeyEvent(code, 0, modifiers));
			return null;
		});
		awaitTicks(intArg(a, "ticks", 0));
		String screen = onClient(() -> {
			for (int i = codes.size() - 1; i >= 0; i--) keyboard.agent_mcp$keyPress(mc.getWindow().handle(), 0, new KeyEvent(codes.get(i), 0, modifiers));
			return screenTitle(mc);
		});
		return text("Pressed " + a.get("keys") + ". Screen now: " + screen);
	}

	private static JsonArray typeText(JsonObject a) throws Exception {
		String s = str(a, "text", "");
		return text(onClient(() -> {
			Screen screen = requireScreen();
			s.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp, 0)));
			return "Typed " + s.length() + " chars into " + screenTitle(Minecraft.getInstance());
		}));
	}

	private static JsonArray mouse(JsonObject a) throws Exception {
		String action = str(a, "action", "click");
		int button = switch (str(a, "button", "left")) {
			case "right" -> 1;
			case "middle" -> 2;
			default -> 0;
		};
		awaitFrames(1); // a screen opened by the previous call positions its list widgets on first render
		return text(onClient(() -> {
			Screen screen = requireScreen();
			double x, y;
			String target = "";
			if (a.has("widget") || a.has("label")) {
				GuiEventListener w = findWidget(screen, a);
				ScreenRectangle r = w.getRectangle();
				x = r.left() + r.width() / 2.0;
				y = r.top() + r.height() / 2.0;
				target = " on '" + label(w) + "'";
			} else {
				x = num(a, "x");
				y = num(a, "y");
			}
			MouseButtonInfo info = new MouseButtonInfo(button, 0);
			screen.mouseMoved(x, y);
			switch (action) {
				case "move" -> {}
				case "scroll" -> screen.mouseScrolled(x, y, 0, a.has("amount") ? a.get("amount").getAsDouble() : -1);
				case "drag" -> {
					double tx = num(a, "to_x"), ty = num(a, "to_y");
					screen.mouseClicked(new MouseButtonEvent(x, y, info), false);
					screen.mouseDragged(new MouseButtonEvent(tx, ty, info), tx - x, ty - y);
					screen.mouseReleased(new MouseButtonEvent(tx, ty, info));
				}
				case "click", "double_click" -> {
					click(screen, x, y, info, false);
					if (action.equals("double_click")) click(screen, x, y, info, true);
				}
				default -> throw new IllegalArgumentException("Unknown action: " + action);
			}
			return String.format(Locale.ROOT, "%s%s at (%.1f, %.1f). Screen now: %s", action, target, x, y, screenTitle(Minecraft.getInstance()));
		}));
	}

	private static JsonArray look(JsonObject a) throws Exception {
		return text(onClient(() -> {
			LocalPlayer p = requirePlayer();
			if (a.has("at")) {
				JsonArray at = a.getAsJsonArray("at");
				p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()));
			} else {
				float yaw = (float) (a.has("yaw") ? num(a, "yaw") : p.getYRot() + (a.has("dyaw") ? num(a, "dyaw") : 0));
				float pitch = Mth.clamp((float) (a.has("pitch") ? num(a, "pitch") : p.getXRot() + (a.has("dpitch") ? num(a, "dpitch") : 0)), -90, 90);
				p.setYRot(yaw);
				p.setXRot(pitch);
				p.yRotO = yaw; // no interpolation from the old angle
				p.xRotO = pitch;
			}
			return String.format(Locale.ROOT, "yaw %.1f pitch %.1f", Mth.wrapDegrees(p.getYRot()), p.getXRot());
		}));
	}

	private static JsonArray chat(JsonObject a) throws Exception {
		String message = str(a, "message", "");
		if (message.isBlank() || message.length() > 256) throw new IllegalArgumentException("message must be 1-256 chars");
		Minecraft mc = Minecraft.getInstance();
		GuiMessage before = onClient(() -> {
			LocalPlayer p = requirePlayer();
			List<GuiMessage> all = chat(mc);
			GuiMessage newest = all.isEmpty() ? null : all.getFirst();
			if (message.startsWith("/")) p.connection.sendCommand(message.substring(1));
			else p.connection.sendChat(message);
			return newest;
		});
		awaitTicks(intArg(a, "wait_ticks", 10));
		List<String> replies = onClient(() -> {
			List<String> out = new ArrayList<>();
			for (GuiMessage m : chat(mc)) {
				if (m == before) break;
				out.addFirst(m.content().getString());
			}
			return out;
		});
		return text(replies.isEmpty() ? "Sent. No new chat lines." : "Sent. New chat lines:\n" + String.join("\n", replies));
	}

	private static JsonArray readLog(JsonObject a) throws Exception {
		Path log = FabricLoader.getInstance().getGameDir().resolve("logs/latest.log");
		Pattern grep = a.has("grep") ? Pattern.compile(a.get("grep").getAsString(), Pattern.CASE_INSENSITIVE) : null;
		List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines()
				.filter(l -> grep == null || grep.matcher(l).find())
				.toList();
		int n = intArg(a, "lines", 80);
		return text(String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size())));
	}

	private static JsonArray world(JsonObject a) throws Exception {
		Minecraft mc = Minecraft.getInstance();
		List<String> worlds = mc.getLevelSource().findLevelCandidates().levels().stream().map(LevelStorageSource.LevelDirectory::directoryName).toList();
		switch (str(a, "action", "")) {
			case "list" -> {
				return text(worlds.isEmpty() ? "No worlds." : String.join("\n", worlds));
			}
			case "open" -> {
				String name = str(a, "name", "");
				if (!worlds.contains(name)) throw new IllegalArgumentException("No world folder '" + name + "'. Worlds: " + worlds);
				onClient(() -> {
					if (mc.level != null) throw new IllegalStateException("Already in a world; leave first");
					mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
					return null;
				});
				return text("Loading '" + name + "'. Poll get_state until in_world is true and no screen is open.");
			}
			case "leave" -> {
				onClient(() -> {
					if (mc.level == null) throw new IllegalStateException("Not in a world");
					mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
					return null;
				});
				return text("Left the world.");
			}
			case "connect" -> {
				String address = str(a, "address", "");
				if (!ServerAddress.isValidAddress(address)) throw new IllegalArgumentException("Invalid address: " + address);
				onClient(() -> {
					if (mc.level != null) throw new IllegalStateException("Already in a world; leave first");
					ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
							new ServerData(address, address, ServerData.Type.OTHER), false, null);
					return null;
				});
				return text("Connecting to " + address + ". Poll get_state until in_world is true and no screen is open.");
			}
			default -> throw new IllegalArgumentException("action must be list, open, leave or connect");
		}
	}

	private static JsonArray shader(JsonObject a) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("iris")) throw new IllegalStateException("Iris is not installed");
		String action = str(a, "action", "status");
		// ponytail: reflection on Iris internals (stable since 1.6) instead of a compile dependency; breaks loudly if renamed.
		return text(onClient(() -> {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			Object config = reflect(null, iris, "getIrisConfig");
			switch (action) {
				case "status" -> {}
				case "list" -> {
					try (var files = Files.list((Path) reflect(null, iris, "getShaderpacksDirectory"))) {
						return "Shader packs:\n" + String.join("\n", files.map(f -> f.getFileName().toString()).sorted().toList());
					}
				}
				case "reload" -> reflect(null, iris, "reload");
				case "enable", "disable", "set_pack" -> {
					if (action.equals("set_pack")) reflect(config, config.getClass(), "setShaderPackName", str(a, "pack", ""));
					reflect(config, config.getClass(), "setShadersEnabled", !action.equals("disable"));
					reflect(config, config.getClass(), "save");
					reflect(null, iris, "reload");
				}
				default -> throw new IllegalArgumentException("Unknown action: " + action);
			}
			return "shaders_enabled=" + reflect(config, config.getClass(), "areShadersEnabled")
					+ " pack=" + reflect(null, iris, "getCurrentPackName") + " (check read_log for compile errors)";
		}));
	}

	private static JsonArray setOption(JsonObject a) throws Exception {
		return text(onClient(() -> {
			Minecraft mc = Minecraft.getInstance();
			Options o = mc.options;
			if (a.has("fov")) o.fov().set(a.get("fov").getAsInt());
			if (a.has("render_distance")) o.renderDistance().set(a.get("render_distance").getAsInt());
			if (a.has("simulation_distance")) o.simulationDistance().set(a.get("simulation_distance").getAsInt());
			if (a.has("gui_scale")) o.guiScale().set(a.get("gui_scale").getAsInt());
			if (a.has("gamma")) o.gamma().set(a.get("gamma").getAsDouble());
			if (a.has("max_fps")) o.framerateLimit().set(a.get("max_fps").getAsInt());
			if (a.has("hide_hud")) o.hideGui = a.get("hide_hud").getAsBoolean();
			if (a.has("window_width") || a.has("window_height")) {
				Window w = mc.getWindow();
				w.setWindowed(intArg(a, "window_width", w.getWidth()), intArg(a, "window_height", w.getHeight()));
			}
			return String.format(Locale.ROOT, "fov=%d render_distance=%d simulation_distance=%d gui_scale=%d gamma=%.2f max_fps=%d hide_hud=%b window=%dx%d",
					o.fov().get(), o.renderDistance().get(), o.simulationDistance().get(), o.guiScale().get(), o.gamma().get(),
					o.framerateLimit().get(), o.hideGui, mc.getWindow().getWidth(), mc.getWindow().getHeight());
		}));
	}

	// ---- GUI helpers ----

	/** Visible widgets in tab order, flattened through containers such as option lists. */
	private static List<GuiEventListener> widgets(Screen screen) {
		List<GuiEventListener> out = new ArrayList<>();
		collect(screen.children(), screen.getRectangle(), out, 0);
		return out;
	}

	private static void collect(List<? extends GuiEventListener> children, ScreenRectangle clip, List<GuiEventListener> out, int depth) {
		for (GuiEventListener c : children) {
			if (out.size() >= MAX_WIDGETS) return;
			if (c instanceof AbstractWidget w && !w.visible) continue;
			if (!c.getRectangle().overlaps(clip)) continue; // scrolled out of its list
			out.add(c);
			if (depth < 4 && c instanceof ContainerEventHandler container) {
				ScreenRectangle inner = c instanceof AbstractSelectionList<?> ? c.getRectangle() : clip;
				collect(container.children(), inner, out, depth + 1);
			}
		}
	}

	private static JsonObject widget(int i, GuiEventListener c) {
		JsonObject o = new JsonObject();
		o.addProperty("i", i);
		o.addProperty("kind", c instanceof EditBox ? "text_field"
				: c instanceof Checkbox ? "checkbox"
				: c instanceof AbstractSliderButton ? "slider"
				: c instanceof AbstractButton ? "button"
				: c instanceof AbstractStringWidget ? "text"
				: c instanceof AbstractSelectionList<?> ? "list"
				: c instanceof ObjectSelectionList.Entry<?> ? "entry"
				: c instanceof ContainerEventHandler ? "group" : "widget");
		String label = label(c);
		if (!label.isEmpty()) o.addProperty("label", label);
		ScreenRectangle r = c.getRectangle();
		JsonArray rect = new JsonArray();
		rect.add(r.left());
		rect.add(r.top());
		rect.add(r.width());
		rect.add(r.height());
		o.add("rect", rect);
		if (c instanceof AbstractWidget w && !w.active) o.addProperty("active", false);
		if (c instanceof EditBox e) o.addProperty("value", e.getValue());
		if (c instanceof Checkbox cb) o.addProperty("checked", cb.selected());
		if (c.isFocused()) o.addProperty("focused", true);
		return o;
	}

	private static String label(GuiEventListener c) {
		if (c instanceof AbstractWidget w) return w.getMessage().getString();
		if (c instanceof ObjectSelectionList.Entry<?> e) return e.getNarration().getString();
		return "";
	}

	private static GuiEventListener findWidget(Screen screen, JsonObject a) {
		List<GuiEventListener> widgets = widgets(screen);
		if (a.has("widget")) {
			int i = a.get("widget").getAsInt();
			if (i < 0 || i >= widgets.size()) throw new IllegalArgumentException("Widget index out of range 0.." + (widgets.size() - 1));
			return widgets.get(i);
		}
		String q = a.get("label").getAsString().toLowerCase(Locale.ROOT);
		return widgets.stream().filter(w -> label(w).toLowerCase(Locale.ROOT).contains(q)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No widget label contains '" + q + "'; see get_state"));
	}

	private static void click(Screen screen, double x, double y, MouseButtonInfo info, boolean doubleClick) {
		MouseButtonEvent event = new MouseButtonEvent(x, y, info);
		screen.afterMouseAction();
		screen.mouseClicked(event, doubleClick);
		screen.mouseReleased(event);
	}

	private static String screenTitle(Minecraft mc) {
		return mc.screen == null ? "none (in game)" : "'" + mc.screen.getTitle().getString() + "'";
	}

	// ---- misc helpers ----

	private static Screen requireScreen() {
		Screen screen = Minecraft.getInstance().screen;
		if (screen == null) throw new IllegalStateException("No screen is open (in game: use press_keys / look)");
		return screen;
	}

	private static LocalPlayer requirePlayer() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) throw new IllegalStateException("Not in a world (use world open/connect)");
		return p;
	}

	private static List<GuiMessage> chat(Minecraft mc) {
		return ((ChatComponentAccessor) mc.gui.getChat()).agent_mcp$getAllMessages();
	}

	private static String describe(Entity e, LocalPlayer p) {
		return String.format(Locale.ROOT, "%s%s d=%.1f at %.1f %.1f %.1f", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
				e.hasCustomName() ? " '" + e.getName().getString() + "'" : "", e.distanceTo(p), e.getX(), e.getY(), e.getZ());
	}

	private static KeyMapping keyMapping(String name) {
		KeyMapping k = KeyMapping.get(name);
		if (k == null) k = KeyMapping.get("key." + name);
		if (k == null) throw new IllegalArgumentException("Unknown key mapping '" + name + "'; see list_keybinds");
		return k;
	}

	/** Largest factor <= wanted that divides both sides (vanilla rejects anything else). */
	private static int downscale(int width, int height, int wanted) {
		for (int f = Math.max(1, wanted); f > 1; f--) {
			if (width % f == 0 && height % f == 0) return f;
		}
		return 1;
	}

	private static Object reflect(Object target, Class<?> cls, String name, Object... args) throws Exception {
		for (Method m : cls.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) {
				try {
					return m.invoke(target, args);
				} catch (InvocationTargetException e) {
					throw e.getCause() instanceof Exception cause ? cause : e;
				}
			}
		}
		throw new NoSuchMethodException(cls.getName() + "." + name + " (unsupported Iris version?)");
	}

	/** Runs on the client (render) thread, which owns all game state. */
	static <T> T onClient(Callable<T> task) throws Exception {
		try {
			return Minecraft.getInstance().submit(() -> {
				try {
					return task.call();
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			}).get(TIMEOUT_S, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
			throw cause instanceof Exception ex ? ex : new RuntimeException(cause);
		}
	}

	private static void awaitTicks(int n) throws Exception {
		long target = Agent_MCPClient.ticks + n;
		await(() -> Agent_MCPClient.ticks >= target, n * 50L + TIMEOUT_S * 1000L, n + " ticks");
	}

	private static void awaitFrames(int n) throws Exception {
		long target = Agent_MCPClient.frames + n;
		await(() -> Agent_MCPClient.frames >= target, n * 1000L + TIMEOUT_S * 1000L, n + " frames");
	}

	private static void await(BooleanSupplier done, long timeoutMs, String what) throws Exception {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!done.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new TimeoutException("Timed out waiting for " + what);
			Thread.sleep(5);
		}
	}

	private static JsonArray text(Object value) {
		JsonObject t = new JsonObject();
		t.addProperty("type", "text");
		t.addProperty("text", value instanceof JsonElement json ? json.toString() : String.valueOf(value));
		JsonArray content = new JsonArray();
		content.add(t);
		return content;
	}

	private static String str(JsonObject a, String key, String def) {
		return a.has(key) ? a.get(key).getAsString() : def;
	}

	private static int intArg(JsonObject a, String key, int def) {
		return a.has(key) ? a.get(key).getAsInt() : def;
	}

	private static double num(JsonObject a, String key) {
		if (!a.has(key)) throw new IllegalArgumentException("Missing '" + key + "'");
		return a.get(key).getAsDouble();
	}

	private static void register(String name, String description, String properties, Handler handler, String... required) {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", JsonParser.parseString(properties));
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) req.add(r);
			schema.add("required", req);
		}
		JsonObject def = new JsonObject();
		def.addProperty("name", name);
		def.addProperty("description", description);
		def.add("inputSchema", schema);
		TOOLS.put(name, new Tool(def, handler));
	}
}
