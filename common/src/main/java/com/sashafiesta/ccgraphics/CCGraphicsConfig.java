package com.sashafiesta.ccgraphics;

import com.electronwill.nightconfig.core.ConfigSpec;
import com.electronwill.nightconfig.core.EnumGetMethod;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.file.FileWatcher;
import com.electronwill.nightconfig.core.io.WritingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class CCGraphicsConfig {
    private static final Logger LOG = LoggerFactory.getLogger(CCGraphicsConfig.class);

    private static final boolean DEFAULT_BANDWIDTH_THROTTLING_ENABLED = true;
    private static final long DEFAULT_BANDWIDTH_REFILL_BYTES_PER_TICK = 128L * 1024L;
    private static final long DEFAULT_BANDWIDTH_CAPACITY_BYTES = 2L * 1024L * 1024L;

    private static final ConfigSpec SPEC = new ConfigSpec();

    static {
        SPEC.define("allow_grayscale_graphics", false, o -> o instanceof Boolean);
        SPEC.define("compression", CompressionType.LZ4_DIFF.name(),
            o -> o instanceof String s && Arrays.stream(CompressionType.values())
                .anyMatch(e -> e.name().equalsIgnoreCase(s)));
        SPEC.define("bandwidth_throttling_enabled", DEFAULT_BANDWIDTH_THROTTLING_ENABLED,
            o -> o instanceof Boolean);
        SPEC.define("bandwidth_refill_bytes_per_tick", DEFAULT_BANDWIDTH_REFILL_BYTES_PER_TICK,
            o -> o instanceof Number n && n.longValue() >= 0L);
        SPEC.define("bandwidth_capacity_bytes", DEFAULT_BANDWIDTH_CAPACITY_BYTES,
            o -> o instanceof Number n && n.longValue() >= 0L);
    }

    private static boolean allowGrayscaleGraphics = false;
    private static volatile boolean bandwidthThrottlingEnabled = DEFAULT_BANDWIDTH_THROTTLING_ENABLED;
    private static volatile long bandwidthRefillBytesPerTick = DEFAULT_BANDWIDTH_REFILL_BYTES_PER_TICK;
    private static volatile long bandwidthCapacityBytes = DEFAULT_BANDWIDTH_CAPACITY_BYTES;
    private static CommentedFileConfig config;

    private CCGraphicsConfig() {}

    public static boolean allowGrayscaleGraphics() {
        return allowGrayscaleGraphics;
    }

    public static void setAllowGrayscaleGraphics(boolean value) {
        allowGrayscaleGraphics = value;
    }

    public static void setCompression(CompressionType type) {
        type.apply();
    }

    public static boolean bandwidthThrottlingEnabled() {
        return bandwidthThrottlingEnabled;
    }

    public static long bandwidthRefillBytesPerTick() {
        return bandwidthRefillBytesPerTick;
    }

    public static long bandwidthCapacityBytes() {
        return bandwidthCapacityBytes;
    }

    public static void setBandwidthThrottlingEnabled(boolean value) {
        bandwidthThrottlingEnabled = value;
    }

    public static void setBandwidthRefillBytesPerTick(long value) {
        bandwidthRefillBytesPerTick = Math.max(0L, value);
    }

    public static void setBandwidthCapacityBytes(long value) {
        bandwidthCapacityBytes = Math.max(0L, value);
    }

    /**
     * Load config using NightConfig. Used by Fabric; NeoForge uses its own config system.
     */
    public static synchronized void load(Path... paths) {
        if (paths.length == 0) return;
        unload();

        var path = Arrays.stream(paths).filter(Files::exists).findFirst().orElseGet(() -> paths[paths.length - 1]);

        config = CommentedFileConfig.builder(path).sync()
            .onFileNotFound(FileNotFoundAction.READ_NOTHING)
            .writingMode(WritingMode.REPLACE)
            .build();

        try {
            Files.createDirectories(path.getParent());
            FileWatcher.defaultInstance().addWatch(config.getNioPath(), CCGraphicsConfig::reload);
        } catch (IOException e) {
            LOG.error("Failed to watch config at {}.", path, e);
        }

        if (reload()) config.save();
    }

    public static synchronized void unload() {
        if (config == null) return;
        config.close();
        FileWatcher.defaultInstance().removeWatch(config.getNioPath());
        config = null;
        allowGrayscaleGraphics = false;
        setBandwidthThrottlingEnabled(DEFAULT_BANDWIDTH_THROTTLING_ENABLED);
        setBandwidthRefillBytesPerTick(DEFAULT_BANDWIDTH_REFILL_BYTES_PER_TICK);
        setBandwidthCapacityBytes(DEFAULT_BANDWIDTH_CAPACITY_BYTES);
        CompressionType.LZ4_DIFF.apply();
    }

    private static synchronized boolean reload() {
        if (config == null) return false;

        LOG.info("Loading ccgraphics config from {}", config.getNioPath());
        config.load();

        var allowedValues = Arrays.stream(CompressionType.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

        var isNew = config.isEmpty();
        config.setComment("allow_grayscale_graphics",
            " Allow graphics mode on non-color (standard) computers and monitors with grayscale rendering.\n" +
            " When false (default), graphics mode is blocked on non-color computers and monitors (CraftOS-PC compatible).");
        config.setComment("compression",
            " Compression algorithm for graphics data sent over the network.\n" +
            " Allowed values: " + allowedValues);
        config.setComment("bandwidth_throttling_enabled",
            " Enable token-bucket throttling on graphics broadcasts.\n" +
            " When disabled, refill rate and capacity are ignored and every frame is sent.");
        config.setComment("bandwidth_refill_bytes_per_tick",
            " Token-bucket refill rate for graphics broadcasts, in bytes per game tick.\n" +
            " Sustained throughput is approximately this value * 20 bytes/second.\n" +
            " Only used when bandwidth_throttling_enabled = true.");
        config.setComment("bandwidth_capacity_bytes",
            " Token-bucket capacity for graphics broadcasts, in bytes.\n" +
            " A single burst can consume up to this many bytes instantly.\n" +
            " New-viewer keyframes always bypass the bucket.\n" +
            " Only used when bandwidth_throttling_enabled = true.");

        var corrected = isNew ? SPEC.correct(config) : SPEC.correct(config, (action, entryPath, oldValue, newValue) ->
            LOG.warn("Corrected config key {} from {} to {}", String.join(".", entryPath), oldValue, newValue));

        allowGrayscaleGraphics = config.getOrElse("allow_grayscale_graphics", false);
        config.<CompressionType>getEnumOrElse("compression", CompressionType.LZ4_DIFF, EnumGetMethod.NAME_IGNORECASE).apply();
        setBandwidthThrottlingEnabled(config.getOrElse("bandwidth_throttling_enabled", DEFAULT_BANDWIDTH_THROTTLING_ENABLED));
        setBandwidthRefillBytesPerTick(config.getLongOrElse("bandwidth_refill_bytes_per_tick", DEFAULT_BANDWIDTH_REFILL_BYTES_PER_TICK));
        setBandwidthCapacityBytes(config.getLongOrElse("bandwidth_capacity_bytes", DEFAULT_BANDWIDTH_CAPACITY_BYTES));

        return corrected > 0;
    }
}
