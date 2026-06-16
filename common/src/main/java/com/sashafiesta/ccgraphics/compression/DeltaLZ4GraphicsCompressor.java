package com.sashafiesta.ccgraphics.compression;

import net.jpountz.lz4.LZ4Factory;

/**
 * Spatial-delta + LZ4: XORs each byte with the previous byte before LZ4. Wins
 * when neighbouring pixels share colours; matches plain LZ4 in cost when they
 * don't. Not a temporal diff - operates on a single frame at a time.
 */
public class DeltaLZ4GraphicsCompressor implements GraphicsCompressor {
    public static final byte TYPE_ID = 2;
    public static final String NAME = "delta_lz4";
    public static final DeltaLZ4GraphicsCompressor INSTANCE = new DeltaLZ4GraphicsCompressor();

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();

    private DeltaLZ4GraphicsCompressor() {}

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public byte[] compress(byte[] data) {
        var delta = new byte[data.length];
        delta[0] = data[0];
        for (var i = 1; i < data.length; i++) {
            delta[i] = (byte) (data[i] - data[i - 1]);
        }
        return FACTORY.fastCompressor().compress(delta);
    }

    @Override
    public byte[] decompress(byte[] data, int expectedSize) {
        var delta = FACTORY.fastDecompressor().decompress(data, expectedSize);
        for (var i = 1; i < delta.length; i++) {
            delta[i] = (byte) (delta[i] + delta[i - 1]);
        }
        return delta;
    }
}
