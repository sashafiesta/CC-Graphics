package com.sashafiesta.ccgraphics;

import dan200.computercraft.api.filesystem.FileOperationException;
import dan200.computercraft.api.filesystem.Mount;
import dan200.computercraft.api.filesystem.MountConstants;
import dan200.computercraft.core.apis.handles.ArrayByteChannel;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A read-only mount backed by files on the classpath (inside the mod JAR).
 * Files are registered at construction time via {@link #addFile(String)}.
 * <p>
 * Errors are reported as {@link FileOperationException} rather than {@code FileNotFoundException} because that is the
 * only {@link IOException} CC:T's {@code MountWrapper} knows how to unwrap: anything else loses both the path and the
 * reason, leaving Lua with a bare (and for the mount root, empty) error message.
 */
public class ClasspathMount implements Mount {
    private final String basePath;
    private final Set<String> files = new HashSet<>();
    private final Set<String> directories = new HashSet<>();

    /**
     * Cache of file contents, keyed by mount-relative path.
     * <p>
     * We have to read a resource in full to learn its size (see {@link #read(String)}), so we hold onto the bytes
     * instead of paying for that read on every {@code fs.getSize}/{@code fs.attributes}/{@code fs.open}. The overlay is
     * a fixed handful of small ROM files, so this stays tiny. Sharing one array between readers is safe because
     * {@link ArrayByteChannel} never writes to its backing array.
     */
    private final Map<String, byte[]> contentCache = new ConcurrentHashMap<>();

    /**
     * @param basePath classpath prefix, e.g. "data/ccgraphics/lua/rom"
     */
    public ClasspathMount(String basePath) {
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        directories.add(""); // root always exists
    }

    /**
     * Register a file path relative to the basePath.
     * E.g. addFile("apis/term.lua") if basePath is "data/ccgraphics/lua/rom".
     */
    public ClasspathMount addFile(String path) {
        files.add(path);
        // Ensure all parent directories are registered
        var parts = path.split("/");
        var dir = new StringBuilder();
        for (var i = 0; i < parts.length - 1; i++) {
            if (i > 0) dir.append("/");
            dir.append(parts[i]);
            directories.add(dir.toString());
        }
        return this;
    }

    @Override
    public boolean exists(String path) {
        return files.contains(path) || directories.contains(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return directories.contains(path);
    }

    @Override
    public void list(String path, List<String> contents) throws IOException {
        if (!directories.contains(path)) {
            throw new FileOperationException(path, files.contains(path) ? MountConstants.NOT_A_DIRECTORY : MountConstants.NO_SUCH_FILE);
        }
        var prefix = path.isEmpty() ? "" : path + "/";
        var seen = new HashSet<String>();
        for (var file : files) {
            if (file.startsWith(prefix)) {
                var rest = file.substring(prefix.length());
                var slash = rest.indexOf('/');
                var entry = slash < 0 ? rest : rest.substring(0, slash);
                if (!entry.isEmpty() && seen.add(entry)) {
                    contents.add(entry);
                }
            }
        }
        for (var dir : directories) {
            if (dir.startsWith(prefix) && !dir.equals(path)) {
                var rest = dir.substring(prefix.length());
                var slash = rest.indexOf('/');
                var entry = slash < 0 ? rest : rest.substring(0, slash);
                if (!entry.isEmpty() && seen.add(entry)) {
                    contents.add(entry);
                }
            }
        }
    }

    /**
     * Directories are registered in a set of their own, so they need an explicit branch here: without one every
     * directory the overlay shadows ({@code /rom}, {@code /rom/apis}, ...) fails {@code fs.getSize}, and takes
     * {@code fs.attributes} down with it because {@link Mount#getAttributes(String)}'s default implementation is
     * written in terms of this method. Zero matches what CC:T's own {@code ArchiveMount} reports for a directory.
     */
    @Override
    public long getSize(String path) throws IOException {
        if (directories.contains(path)) return 0;
        if (!files.contains(path)) throw new FileOperationException(path, MountConstants.NO_SUCH_FILE);
        return read(path).length;
    }

    @Override
    public SeekableByteChannel openForRead(String path) throws IOException {
        if (!files.contains(path)) {
            throw new FileOperationException(path, directories.contains(path) ? MountConstants.NOT_A_FILE : MountConstants.NO_SUCH_FILE);
        }
        return new ArrayByteChannel(read(path));
    }

    /**
     * Read a registered file, caching its contents.
     * <p>
     * This deliberately reads the whole resource rather than trusting {@code InputStream#available()}, which is only
     * contractually an estimate of what can be read without blocking. {@code ZipFile}'s inflater stream happens to
     * report the true entry size, but Fabric and NeoForge both serve mod resources through their own classloaders and
     * file systems, so that is not the stream we are guaranteed to get: a bare {@code InflaterInputStream} reports 1,
     * and a stream over a non-seekable channel reports 0. Either would make {@code fs.getSize} and
     * {@code fs.attributes().size} lie about every overlaid file.
     */
    private byte[] read(String path) throws IOException {
        var cached = contentCache.get(path);
        if (cached != null) return cached;

        var resource = basePath + "/" + path;
        try (var stream = ClasspathMount.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new FileOperationException(path, MountConstants.NO_SUCH_FILE);
            var bytes = stream.readAllBytes();
            contentCache.put(path, bytes);
            return bytes;
        }
    }
}
