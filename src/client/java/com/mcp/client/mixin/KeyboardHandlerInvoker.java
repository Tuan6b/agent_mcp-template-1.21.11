package com.mcp.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
	/** The real GLFW key callback: action 1 = press, 0 = release. */
	@Invoker("keyPress")
	void agent_mcp$keyPress(long window, int action, KeyEvent event);
}
