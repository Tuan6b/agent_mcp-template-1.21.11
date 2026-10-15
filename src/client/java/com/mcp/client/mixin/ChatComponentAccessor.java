package com.mcp.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	/** GuiMessage entries, newest first, capped at 100 by vanilla. Untyped because GuiMessage moved package in 26.x. */
	@Accessor("allMessages")
	List<?> agent_mcp$getAllMessages();
}
