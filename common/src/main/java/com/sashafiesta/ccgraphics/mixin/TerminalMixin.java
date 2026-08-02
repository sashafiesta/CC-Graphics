package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.core.terminal.Terminal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Adds the graphics-mode state to every {@code Terminal}: pixel buffer (sized
 * {@code width * 6} by {@code height * 9}, filled with colour 15), current mode,
 * frozen flag, 240-entry extended palette and graphics-disabled flag. Hooks
 * {@code init}, {@code clear}, {@code reset} and {@code resize} so the buffer
 * stays consistent with the text-side terminal.
 */
@Mixin(value = Terminal.class, remap = false)
abstract class TerminalMixin implements IGraphicsTerminal {
    private static final int PIXELS_W = 6;
    private static final int PIXELS_H = 9;

    @Shadow protected int width;
    @Shadow protected int height;
    @Shadow protected boolean colour;
    @Shadow protected int cursorBackgroundColour;

    @Unique private int ccgraphics$graphicsMode = 0;
    @Unique private boolean ccgraphics$frozen = false;
    @Unique private byte[] ccgraphics$graphics;
    @Unique private boolean ccgraphics$graphicsDisabled = false;

    @Unique private int[] ccgraphics$extPaletteARGB = new int[240];
    @Unique private double[][] ccgraphics$extPaletteRGB = new double[240][3];

    @Inject(method = "<init>(IIZLjava/lang/Runnable;)V", at = @At("TAIL"))
    private void ccgraphics$onInit(int width, int height, boolean colour, Runnable changedCallback, CallbackInfo ci) {
        ccgraphics$graphics = new byte[width * PIXELS_W * height * PIXELS_H];
        Arrays.fill(ccgraphics$graphics, (byte) 0x0F);
        ccgraphics$resetExtPalette();
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void ccgraphics$onClear(CallbackInfo ci) {
        if (ccgraphics$graphicsMode > 0 && ccgraphics$graphics != null) {
            Arrays.fill(ccgraphics$graphics, (byte) 0x0F);
        }
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void ccgraphics$onReset(CallbackInfo ci) {
        ccgraphics$graphicsMode = 0;
        ccgraphics$frozen = false;
        if (ccgraphics$graphics != null) Arrays.fill(ccgraphics$graphics, (byte) 0x0F);
        ccgraphics$resetExtPalette();
    }

    @Inject(method = "resize", at = @At("TAIL"))
    private void ccgraphics$onResize(int width, int height, CallbackInfo ci) {
        // On resize, allocate a fresh buffer of the new dimensions filled with
        // 0x0F (renders as black after the 15-x palette flip). Mode, frozen
        // flag, and palettes are left intact - the program can detect
        // monitor_resize and redraw at will.
        ccgraphics$graphics = new byte[this.width * PIXELS_W * this.height * PIXELS_H];
        Arrays.fill(ccgraphics$graphics, (byte) 0x0F);
        // The buffer every cached frame and every diff base described is gone,
        // and buffer length alone does not reveal that: an area-preserving
        // reshape (18x26 -> 39x12) keeps the byte count identical while making
        // every previous frame meaningless.
        ccgraphics$invalidateGraphicsSync();
    }

    /**
     * No-op base: a plain {@code Terminal} keeps no diff chain. Overridden by
     * {@code NetworkedTerminalMixin}, which owns the state this discards.
     */
    @Override
    public void ccgraphics$invalidateGraphicsSync() {
    }

    @Unique
    private void ccgraphics$resetExtPalette() {
        ccgraphics$extPaletteARGB = new int[240];
        Arrays.fill(ccgraphics$extPaletteARGB, 0xFF000000);
        ccgraphics$extPaletteRGB = new double[240][3];
    }

    @Unique
    private void ccgraphics$notifyChanged() {
        if (!ccgraphics$frozen) {
            ((Terminal)(Object)this).setChanged();
        }
    }

    @Override
    public int ccgraphics$getGraphicsMode() { return ccgraphics$graphicsMode; }

    @Override
    public void ccgraphics$setGraphicsMode(int mode) {
        if (mode != 0 && mode != 1 && mode != 2) return;
        ccgraphics$graphicsMode = mode;
        ((Terminal)(Object)this).setChanged();
    }

    @Override
    public int ccgraphics$getGraphicsWidth() { return width * PIXELS_W; }

    @Override
    public int ccgraphics$getGraphicsHeight() { return height * PIXELS_H; }

    @Override
    public byte[] ccgraphics$getGraphics() {
        if (ccgraphics$graphics == null) {
            ccgraphics$graphics = new byte[width * PIXELS_W * height * PIXELS_H];
            Arrays.fill(ccgraphics$graphics, (byte) 0x0F);
        }
        return ccgraphics$graphics;
    }

    @Override
    public void ccgraphics$setPixel(int x, int y, int colorIndex) {
        var gw = ccgraphics$getGraphicsWidth();
        var gh = ccgraphics$getGraphicsHeight();
        var maxIndex = ccgraphics$graphicsMode == 2 ? 255 : 15;
        if (x < 0 || x >= gw || y < 0 || y >= gh || colorIndex < 0 || colorIndex > maxIndex) return;
        ccgraphics$getGraphics()[y * gw + x] = (byte) colorIndex;
        ccgraphics$notifyChanged();
    }

    @Override
    public int ccgraphics$getPixel(int x, int y) {
        var gw = ccgraphics$getGraphicsWidth();
        var gh = ccgraphics$getGraphicsHeight();
        if (x < 0 || x >= gw || y < 0 || y >= gh) return -1;
        return ccgraphics$getGraphics()[y * gw + x] & 0xFF;
    }

    @Override
    public void ccgraphics$setPixelBlock(int startX, int startY, byte[] data, int dataOffset, int stride, int blockWidth, int blockHeight) {
        var gw = ccgraphics$getGraphicsWidth();
        var gh = ccgraphics$getGraphicsHeight();
        var buf = ccgraphics$getGraphics();
        for (var row = 0; row < blockHeight; row++) {
            var y = startY + row;
            if (y < 0 || y >= gh) continue;
            var srcX = 0;
            var dstX = startX;
            var copyWidth = blockWidth;
            if (dstX < 0) { srcX = -dstX; copyWidth += dstX; dstX = 0; }
            if (dstX + copyWidth > gw) copyWidth = gw - dstX;
            if (copyWidth <= 0) continue;
            System.arraycopy(data, dataOffset + row * stride + srcX, buf, y * gw + dstX, copyWidth);
        }
        ccgraphics$notifyChanged();
    }

    @Override
    public void ccgraphics$fillPixels(int x, int y, int w, int h, byte colorIndex) {
        var gw = ccgraphics$getGraphicsWidth();
        var gh = ccgraphics$getGraphicsHeight();
        var x0 = Math.max(x, 0);
        var y0 = Math.max(y, 0);
        // Long maths for the far edges: term.drawPixels passes w/h through unclamped, so
        // x + w overflows to negative for a large enough width and the region collapses to
        // empty - drawing nothing where CraftOS-PC fills to the screen edge.
        var x1 = (int) Math.min((long) x + w, gw);
        var y1 = (int) Math.min((long) y + h, gh);
        if (x0 >= x1 || y0 >= y1) return;
        var buf = ccgraphics$getGraphics();
        for (var row = y0; row < y1; row++) {
            Arrays.fill(buf, row * gw + x0, row * gw + x1, colorIndex);
        }
        ccgraphics$notifyChanged();
    }

    @Override
    public void ccgraphics$setFrozen(boolean frozen) {
        var wasFrozen = ccgraphics$frozen;
        ccgraphics$frozen = frozen;
        if (wasFrozen && !frozen) {
            ((Terminal)(Object)this).setChanged();
        }
    }

    @Override
    public boolean ccgraphics$getFrozen() { return ccgraphics$frozen; }

    @Override
    public int ccgraphics$getExtPaletteARGB(int index) {
        if (index < 16 || index > 255) return 0xFF000000;
        return ccgraphics$extPaletteARGB[index - 16];
    }

    @Override
    public void ccgraphics$setExtPaletteColor(int index, double r, double g, double b) {
        if (index < 16 || index > 255) return;
        if (!colour) {
            var grey = (r + g + b) / 3.0;
            r = grey; g = grey; b = grey;
        }
        var i = index - 16;
        ccgraphics$extPaletteRGB[i] = new double[]{ r, g, b };
        var ri = (int) (r * 255) & 0xFF;
        var gi = (int) (g * 255) & 0xFF;
        var bi = (int) (b * 255) & 0xFF;
        ccgraphics$extPaletteARGB[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        ccgraphics$notifyChanged();
    }

    @Override
    public double[] ccgraphics$getExtPaletteColor(int index) {
        if (index < 16 || index > 255) return new double[]{ 0, 0, 0 };
        return ccgraphics$extPaletteRGB[index - 16];
    }

    @Override
    public int[] ccgraphics$getExtPaletteData() {
        return Arrays.copyOf(ccgraphics$extPaletteARGB, ccgraphics$extPaletteARGB.length);
    }

    @Override
    public boolean ccgraphics$isGraphicsDisabled() { return ccgraphics$graphicsDisabled; }

    @Override
    public void ccgraphics$setGraphicsDisabled(boolean disabled) {
        ccgraphics$graphicsDisabled = disabled;
        if (disabled && ccgraphics$graphicsMode > 0) {
            ccgraphics$setGraphicsMode(0);
        }
    }

    @Override
    public void ccgraphics$setExtPaletteData(int[] data) {
        if (data == null || data.length != EXT_PALETTE_SIZE) return;
        System.arraycopy(data, 0, ccgraphics$extPaletteARGB, 0, EXT_PALETTE_SIZE);
        for (var i = 0; i < EXT_PALETTE_SIZE; i++) {
            var argb = data[i];
            ccgraphics$extPaletteRGB[i] = new double[]{
                ((argb >> 16) & 0xFF) / 255.0,
                ((argb >> 8) & 0xFF) / 255.0,
                (argb & 0xFF) / 255.0
            };
        }
    }
}
