package com.sashafiesta.ccgraphics.duck;

/**
 * Duck type added to dan200's {@code Terminal} by {@code TerminalMixin}. Carries
 * the per-terminal graphics state: pixel buffer, current mode (0/1/2), 240-entry
 * extended palette, frozen flag, keyframe-request flag and graphics-disabled
 * flag. Cast a {@code Terminal} to this to reach any graphics-mode operation.
 */
public interface IGraphicsTerminal {
    int ccgraphics$getGraphicsMode();

    void ccgraphics$setGraphicsMode(int mode);

    int ccgraphics$getGraphicsWidth();

    int ccgraphics$getGraphicsHeight();

    byte[] ccgraphics$getGraphics();

    void ccgraphics$setPixel(int x, int y, int colorIndex);

    int ccgraphics$getPixel(int x, int y);

    void ccgraphics$setPixelBlock(int startX, int startY, byte[] data, int dataOffset, int stride, int blockWidth, int blockHeight);

    void ccgraphics$fillPixels(int x, int y, int w, int h, byte colorIndex);

    void ccgraphics$setFrozen(boolean frozen);

    boolean ccgraphics$getFrozen();

    int ccgraphics$getExtPaletteARGB(int index);

    void ccgraphics$setExtPaletteColor(int index, double r, double g, double b);

    double[] ccgraphics$getExtPaletteColor(int index);

    int[] ccgraphics$getExtPaletteData();

    void ccgraphics$setExtPaletteData(int[] data);

    void ccgraphics$requestKeyframe();

    boolean ccgraphics$consumeKeyframeRequest();

    boolean ccgraphics$isGraphicsDisabled();

    void ccgraphics$setGraphicsDisabled(boolean disabled);
}
