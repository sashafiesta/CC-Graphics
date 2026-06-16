package com.sashafiesta.ccgraphics.compression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy for compressing a graphics frame. Each implementation has a unique
 * {@link #typeId()} so the wire payload can carry the algorithm out-of-band and
 * the receiver can pick the matching decompressor.
 * <p>
 * Implementations are registered once at startup. Lookup is by {@link #forName}
 * or {@link #forTypeId}; an unknown id falls back to {@link RawGraphicsCompressor}.
 */
public interface GraphicsCompressor {
    byte typeId();

    String name();

    byte[] compress(byte[] data);

    byte[] decompress(byte[] data, int expectedSize);

    default boolean isDiff() {
        return false;
    }

    default boolean hasTimedKeyframes() {
        return false;
    }

    static void register(GraphicsCompressor compressor) {
        Registry.register(compressor);
    }

    static GraphicsCompressor forTypeId(byte typeId) {
        return Registry.byTypeId(typeId);
    }

    static GraphicsCompressor forName(String name) {
        return Registry.byName(name);
    }

    static GraphicsCompressor defaultCompressor() {
        return Registry.defaultCompressor();
    }

    static void setDefault(String name) {
        Registry.setDefault(name);
    }

    static Map<String, GraphicsCompressor> all() {
        return Registry.all();
    }
}

final class Registry {
    private static final Map<String, GraphicsCompressor> byName = new LinkedHashMap<>();
    private static final GraphicsCompressor[] byTypeId = new GraphicsCompressor[256];
    private static GraphicsCompressor defaultCompressor;

    static {
        register(RawGraphicsCompressor.INSTANCE);
        register(LZ4GraphicsCompressor.INSTANCE);
        register(DeltaLZ4GraphicsCompressor.INSTANCE);
        register(Lz4DiffGraphicsCompressor.INSTANCE);
        register(Lz4AdaptiveDiffGraphicsCompressor.INSTANCE);
        defaultCompressor = Lz4DiffGraphicsCompressor.INSTANCE;
    }

    static void register(GraphicsCompressor compressor) {
        var id = compressor.typeId() & 0xFF;
        if (byTypeId[id] != null) {
            throw new IllegalArgumentException("Compressor type ID " + id + " already registered");
        }
        if (byName.containsKey(compressor.name())) {
            throw new IllegalArgumentException("Compressor name '" + compressor.name() + "' already registered");
        }
        byTypeId[id] = compressor;
        byName.put(compressor.name(), compressor);
    }

    static GraphicsCompressor byTypeId(byte typeId) {
        var compressor = byTypeId[typeId & 0xFF];
        return compressor != null ? compressor : RawGraphicsCompressor.INSTANCE;
    }

    static GraphicsCompressor byName(String name) {
        return byName.getOrDefault(name, RawGraphicsCompressor.INSTANCE);
    }

    static GraphicsCompressor defaultCompressor() {
        return defaultCompressor;
    }

    static void setDefault(String name) {
        var compressor = byName.get(name);
        if (compressor == null) {
            throw new IllegalArgumentException("Unknown compressor: '" + name + "'");
        }
        defaultCompressor = compressor;
    }

    static Map<String, GraphicsCompressor> all() {
        return Collections.unmodifiableMap(byName);
    }
}
