package com.mcp.client.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	/** Newest first, capped at 100 by vanilla. */
	@Accessor("allMessages")
	List<GuiMessage> agent_mcp$getAllMessages();
}
