package com.mcp.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("clickCount")
	int agent_mcp$getClickCount();

	@Accessor("clickCount")
	void agent_mcp$setClickCount(int count);
}
