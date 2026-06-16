package com.sashafiesta.ccgraphics.duck;

/**
 * Duck type added to dan200's {@code TerminalState} by {@code TerminalStateMixin}.
 * Carries the wire-side graphics payload - mode, compressor id, byte array - plus
 * the optional 256-color palette used in mode 2. Cast a {@code TerminalState} to
 * this when constructing or applying a snapshot over the network or in NBT.
 */
public interface IGraphicsTerminalState {
    int ccgraphics$getGraphicsMode();

    byte ccgraphics$getGraphicsCompressionType();

    byte[] ccgraphics$getGraphicsData();

    void ccgraphics$setGraphicsData(int mode, byte compressionType, byte[] data);

    int[] ccgraphics$getExtPaletteData();

    void ccgraphics$setExtPaletteData(int[] data);
}
