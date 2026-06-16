package com.sashafiesta.ccgraphics.forge;

import com.sashafiesta.ccgraphics.CCGraphicsConfig;
import com.sashafiesta.ccgraphics.CCGraphicsDataComponents;
import com.sashafiesta.ccgraphics.CompressionType;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge entry point. Registers the {@code graphics_disabled} data component
 * via {@link DeferredRegister} and exposes the addon's runtime settings through
 * Forge's {@link ModConfigSpec}, mirroring them into {@link CCGraphicsConfig} on
 * each config event.
 */
@Mod("ccgraphics")
public class CCGraphicsForge {
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, "ccgraphics");

    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> GRAPHICS_DISABLED =
        COMPONENTS.register("graphics_disabled", () -> {
            var type = CCGraphicsDataComponents.buildGraphicsDisabled();
            CCGraphicsDataComponents.GRAPHICS_DISABLED = type;
            return type;
        });

    private static ModConfigSpec.BooleanValue allowGrayscaleGraphics;
    private static ModConfigSpec.EnumValue<CompressionType> compression;
    private static ModConfigSpec.BooleanValue bandwidthThrottlingEnabled;
    private static ModConfigSpec.LongValue bandwidthRefillBytesPerTick;
    private static ModConfigSpec.LongValue bandwidthCapacityBytes;

    public CCGraphicsForge(IEventBus modBus, ModContainer container) {
        COMPONENTS.register(modBus);

        var builder = new ModConfigSpec.Builder();
        allowGrayscaleGraphics = builder
            .comment(
                "Allow graphics mode on non-color (standard) computers and monitors with grayscale rendering.",
                "When false (default), graphics mode is blocked on non-color computers and monitors (CraftOS-PC compatible)."
            )
            .define("allow_grayscale_graphics", false);
        compression = builder
            .comment(
                "Compression algorithm for graphics data sent over the network."
            )
            .defineEnum("compression", CompressionType.LZ4_DIFF);
        bandwidthThrottlingEnabled = builder
            .comment(
                "Enable token-bucket throttling on graphics broadcasts.",
                "When disabled, refill rate and capacity are ignored and every frame is sent."
            )
            .define("bandwidth_throttling_enabled", true);
        bandwidthRefillBytesPerTick = builder
            .comment(
                "Token-bucket refill rate for graphics broadcasts, in bytes per game tick.",
                "Sustained throughput is approximately this value * 20 bytes/second.",
                "Only used when bandwidth_throttling_enabled = true."
            )
            .defineInRange("bandwidth_refill_bytes_per_tick", 128L * 1024L, 0L, Long.MAX_VALUE);
        bandwidthCapacityBytes = builder
            .comment(
                "Token-bucket capacity for graphics broadcasts, in bytes.",
                "A single burst can consume up to this many bytes instantly.",
                "New-viewer keyframes always bypass the bucket.",
                "Only used when bandwidth_throttling_enabled = true."
            )
            .defineInRange("bandwidth_capacity_bytes", 2L * 1024L * 1024L, 0L, Long.MAX_VALUE);
        var spec = builder.build();

        container.registerConfig(ModConfig.Type.SERVER, spec);
        modBus.addListener(CCGraphicsForge::onConfigEvent);
    }

    private static void onConfigEvent(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) return;
        CCGraphicsConfig.setAllowGrayscaleGraphics(allowGrayscaleGraphics.get());
        CCGraphicsConfig.setCompression(compression.get());
        CCGraphicsConfig.setBandwidthThrottlingEnabled(bandwidthThrottlingEnabled.get());
        CCGraphicsConfig.setBandwidthRefillBytesPerTick(bandwidthRefillBytesPerTick.get());
        CCGraphicsConfig.setBandwidthCapacityBytes(bandwidthCapacityBytes.get());
    }
}
