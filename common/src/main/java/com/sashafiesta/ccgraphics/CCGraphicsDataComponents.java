package com.sashafiesta.ccgraphics;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Holds the {@link DataComponentType} instances this mod registers. The
 * per-platform entry point populates {@link #GRAPHICS_DISABLED} during init;
 * common code reads it.
 */
public final class CCGraphicsDataComponents {
    public static DataComponentType<Boolean> GRAPHICS_DISABLED;

    private CCGraphicsDataComponents() {}

    public static DataComponentType<Boolean> buildGraphicsDisabled() {
        return DataComponentType.<Boolean>builder()
            .persistent(Codec.BOOL)
            .networkSynchronized(ByteBufCodecs.BOOL)
            .build();
    }
}
