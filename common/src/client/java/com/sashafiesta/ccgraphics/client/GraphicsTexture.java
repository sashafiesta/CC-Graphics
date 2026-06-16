package com.sashafiesta.ccgraphics.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Holds a {@link NativeImage}/{@link DynamicTexture} pair sized to a terminal's
 * graphics buffer, and uploads the buffer's colours through the terminal's palette.
 * <p>
 * Used by both the in-GUI terminal widget and the monitor block-entity renderer.
 * Not thread-safe; expect all calls from the client render thread.
 */
public final class GraphicsTexture implements AutoCloseable {
    private final String name;
    private NativeImage image;
    private DynamicTexture texture;
    private ResourceLocation location;
    private int width;
    private int height;

    public GraphicsTexture(String name) {
        this.name = name;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Ensure the texture matches the terminal's pixel grid and, if {@code redraw} or a
     * resize happened, repaint it from the buffer.
     *
     * @return The dynamic texture's location, suitable for blit or as a render-type
     * argument. Never {@code null} once this has been called at least once.
     */
    public ResourceLocation update(Terminal terminal, boolean redraw) {
        var gfx = (IGraphicsTerminal) terminal;
        var gw = gfx.ccgraphics$getGraphicsWidth();
        var gh = gfx.ccgraphics$getGraphicsHeight();

        var recreated = false;
        if (image == null || width != gw || height != gh) {
            close();
            width = gw;
            height = gh;
            image = new NativeImage(NativeImage.Format.RGBA, gw, gh, false);
            texture = new DynamicTexture(image);
            // Pixel-art rendering wants NEAREST; the GL default is LINEAR for 3D draws.
            texture.setFilter(false, false);
            location = Minecraft.getInstance().getTextureManager().register(name, texture);
            recreated = true;
        }

        if (redraw || recreated) {
            var buf = gfx.ccgraphics$getGraphics();
            var palette = terminal.getPalette();
            for (var y = 0; y < gh; y++) {
                for (var x = 0; x < gw; x++) {
                    int argb;
                    var colorIndex = buf[y * gw + x] & 0xFF;
                    if (colorIndex < 16) {
                        argb = palette.getRenderColours(15 - colorIndex);
                    } else {
                        argb = gfx.ccgraphics$getExtPaletteARGB(colorIndex);
                    }
                    var a = (argb >> 24) & 0xFF;
                    var r = (argb >> 16) & 0xFF;
                    var g = (argb >> 8) & 0xFF;
                    var b = argb & 0xFF;
                    var abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    image.setPixelRGBA(x, y, abgr);
                }
            }
            texture.upload();
        }

        return location;
    }

    @Override
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
            image = null;
        }
        if (location != null) {
            Minecraft.getInstance().getTextureManager().release(location);
            location = null;
        }
        width = 0;
        height = 0;
    }
}
