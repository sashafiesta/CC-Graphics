package com.sashafiesta.ccgraphics;

import dan200.computercraft.api.filesystem.Mount;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A read-only mount that overlays one mount on top of another.
 * Files in the overlay take precedence over the base mount.
 */
public class OverlayMount implements Mount {
    private final Mount base;
    private final Mount overlay;

    public OverlayMount(Mount base, Mount overlay) {
        this.base = base;
        this.overlay = overlay;
    }

    @Override
    public boolean exists(String path) throws IOException {
        return overlay.exists(path) || base.exists(path);
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        // A path is a directory if it's a directory in either mount
        if (overlay.exists(path)) {
            if (overlay.isDirectory(path)) return true;
            // Overlay has a file here - not a directory, even if base has a directory
            return false;
        }
        return base.exists(path) && base.isDirectory(path);
    }

    /**
     * Both mounts list into a scratch list rather than straight into {@code contents}. {@link Mount#list} is an
     * accumulator - the contract is "add all the file names to this list", not "fill this empty list" - so the caller
     * may hand us entries it gathered elsewhere. Deduplicating against those would let names that have nothing to do
     * with this directory shadow, and so silently drop, real overlay entries.
     */
    @Override
    public void list(String path, List<String> contents) throws IOException {
        var listed = new ArrayList<String>();

        // Collect from base first, so its entries keep their original ordering
        if (base.exists(path) && base.isDirectory(path)) base.list(path, listed);

        // Then the overlay, which contributes anything the base does not already have
        if (overlay.exists(path) && overlay.isDirectory(path)) overlay.list(path, listed);

        var seen = new HashSet<String>();
        for (var entry : listed) {
            if (seen.add(entry)) contents.add(entry);
        }
    }

    @Override
    public long getSize(String path) throws IOException {
        if (overlay.exists(path)) return overlay.getSize(path);
        return base.getSize(path);
    }

    @Override
    public SeekableByteChannel openForRead(String path) throws IOException {
        if (overlay.exists(path)) return overlay.openForRead(path);
        return base.openForRead(path);
    }

    @Override
    public BasicFileAttributes getAttributes(String path) throws IOException {
        if (overlay.exists(path)) return overlay.getAttributes(path);
        return base.getAttributes(path);
    }
}
