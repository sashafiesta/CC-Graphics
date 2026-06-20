package com.sashafiesta.ccgraphics.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render types used to draw graphics-mode pixel content. Delegates to
 * {@link RenderType#text(ResourceLocation)} so the brightness pipeline matches
 * the one CC:T's VBO monitor renderer uses for terminal text - vertices carry
 * {@code FULL_BRIGHT_LIGHTMAP}, the shader multiplies sample × vertex color ×
 * lightmap, the lightmap at the corner is ~0.94-1.0 depending on scene
 * lighting. A graphics-mode image therefore reads at the same brightness as
 * the same image rendered as braille characters via the text pipeline.
 */
public final class GraphicsRenderTypes {
    private GraphicsRenderTypes() {}

    public static RenderType fullbright(ResourceLocation texture) {
        return RenderType.text(texture);
    }
}
