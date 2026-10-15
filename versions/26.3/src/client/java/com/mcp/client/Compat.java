package com.mcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.lwjgl.sdl.SDLKeyboard;

/** Minecraft 26.3 side of the APIs that moved in 26.x. Every versions/ project has a Compat with these methods. */
final class Compat {
	static Screen screen(Minecraft mc) {
		return mc.gui.screen();
	}

	static void setScreen(Minecraft mc, Screen screen) {
		mc.gui.setScreen(screen);
	}

	static boolean loadingOverlay(Minecraft mc) {
		return mc.gui.overlay() != null;
	}

	static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.gameRenderer.mainRenderTarget();
	}

	static boolean hudHidden(Minecraft mc) {
		return mc.gui.hud.isHidden();
	}

	static void setHudHidden(Minecraft mc, boolean hidden) {
		if (mc.gui.hud.isHidden() != hidden) mc.gui.hud.toggle(); // 26.3 only exposes a toggle
	}

	static ChatComponent chat(Minecraft mc) {
		return mc.gui.hud.getChat();
	}

	static String chatText(Object guiMessage) {
		return ((GuiMessage) guiMessage).content().getString();
	}

	static long dayTime(ClientLevel level) {
		return level.getDefaultClockTime();
	}

	static boolean isKeyboard(InputConstants.Key key) {
		return key.getType() == InputConstants.Type.KEYBOARD;
	}

	static CharacterEvent charEvent(int codepoint) {
		return new CharacterEvent(codepoint);
	}

	/** Same shape as SDLEventHandler: the layout keycode drives shortcuts such as Ctrl+A in text fields. */
	static KeyEvent keyEvent(int scancode, int modifiers) {
		return new KeyEvent(scancode, SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true), modifiers);
	}
}
