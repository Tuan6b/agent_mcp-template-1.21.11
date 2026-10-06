package com.mcp.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mcp.client.Agent_MCPClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void agent_mcp$countFrame(boolean renderLevel, CallbackInfo ci) {
		Agent_MCPClient.frames++;
	}

	// Holding attack only keeps mining while the mouse is grabbed, which never happens while the window is unfocused.
	@ModifyExpressionValue(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean agent_mcp$grabbedWhileAgentAttacks(boolean grabbed) {
		return grabbed || Agent_MCPClient.HELD.containsKey(((Minecraft) (Object) this).options.keyAttack);
	}
}
