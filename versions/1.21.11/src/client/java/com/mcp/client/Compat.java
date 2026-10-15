package com.mcp.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;

/** Minecraft 1.21.11 side of the APIs that moved in 26.x. Every versions/ project has a Compat with these methods. */
final class Compat {
	static Screen screen(Minecraft mc) {
		return mc.screen;
	}

	static void setScreen(Minecraft mc, Screen screen) {
		mc.setScreen(screen);
	}

	static boolean loadingOverlay(Minecraft mc) {
		return mc.getOverlay() != null;
	}

	static RenderTarget mainRenderTarget(Minecraft mc) {
		return mc.getMainRenderTarget();
	}

	static boolean hudHidden(Minecraft mc) {
		return mc.options.hideGui;
	}

	static void setHudHidden(Minecraft mc, boolean hidden) {
		mc.options.hideGui = hidden;
	}

	static ChatComponent chat(Minecraft mc) {
		return mc.gui.getChat();
	}

	static String chatText(Object guiMessage) {
		return ((GuiMessage) guiMessage).content().getString();
	}

	static long dayTime(ClientLevel level) {
		return level.getDayTime();
	}

	static boolean isKeyboard(InputConstants.Key key) {
		return key.getType() == InputConstants.Type.KEYSYM;
	}

	static CharacterEvent charEvent(int codepoint) {
		return new CharacterEvent(codepoint, 0);
	}

	static KeyEvent keyEvent(int key, int modifiers) {
		return new KeyEvent(key, 0, modifiers);
	}
}
