package com.sashafiesta.ccgraphics.duck;

/**
 * Duck type added to dan200's {@code Terminal} by {@code TerminalMixin}. Carries
 * the per-terminal graphics state: pixel buffer, current mode (0/1/2), 240-entry
 * extended palette, frozen flag and graphics-disabled flag. Cast a
 * {@code Terminal} to this to reach any graphics-mode operation.
 */
public interface IGraphicsTerminal {
    /**
     * Entries in the extended palette - indices 16..255. Named here because it
     * is the contract {@link #ccgraphics$setExtPaletteData(int[])} enforces, and
     * anything deserialising a palette has to check against it before handing
     * the array over.
     */
    int EXT_PALETTE_SIZE = 240;

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

    boolean ccgraphics$isGraphicsDisabled();

    void ccgraphics$setGraphicsDisabled(boolean disabled);

    /**
     * Discard any frame-sync state derived from the current pixel buffer, called
     * whenever that buffer is replaced.
     * <p>
     * Lives on this interface rather than beside the state it clears because the
     * buffer is replaced by {@code Terminal.resize}, while the diff chain, the
     * cached keyframe and the received-keyframe flag are all
     * {@code NetworkedTerminal}'s. A plain {@code Terminal} has none of them and
     * implements this as a no-op.
     */
    void ccgraphics$invalidateGraphicsSync();
}
