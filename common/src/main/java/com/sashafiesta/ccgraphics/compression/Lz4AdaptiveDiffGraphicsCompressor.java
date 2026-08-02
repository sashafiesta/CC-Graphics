package com.sashafiesta.ccgraphics.compression;

import net.jpountz.lz4.LZ4Factory;

/**
 * Adaptive diff compressor: identical wire encoding to {@link Lz4DiffGraphicsCompressor},
 * but leaves {@link #hasTimedKeyframes()} at {@code false}, so there is no periodic
 * keyframe. {@code NetworkedTerminalMixin} then emits one only when it has to - no
 * usable previous frame (first frame after entering graphics mode, or a resize), or
 * an explicit request (new viewer) - plus the adaptive case: whenever the LZ4'd full
 * frame turns out smaller than the LZ4'd diff, which is what a large change looks like.
 */
public class Lz4AdaptiveDiffGraphicsCompressor implements GraphicsCompressor {
    public static final byte TYPE_ID = 4;
    public static final String NAME = "lz4_adiff";
    public static final Lz4AdaptiveDiffGraphicsCompressor INSTANCE = new Lz4AdaptiveDiffGraphicsCompressor();

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();

    private Lz4AdaptiveDiffGraphicsCompressor() {}

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isDiff() {
        return true;
    }

    @Override
    public byte[] compress(byte[] data) {
        return FACTORY.fastCompressor().compress(data);
    }

    @Override
    public byte[] decompress(byte[] data, int expectedSize) {
        return FACTORY.fastDecompressor().decompress(data, expectedSize);
    }
}
