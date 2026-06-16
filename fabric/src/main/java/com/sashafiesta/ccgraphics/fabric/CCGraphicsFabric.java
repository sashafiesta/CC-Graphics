package com.sashafiesta.ccgraphics.fabric;

import com.sashafiesta.ccgraphics.CCGraphicsConfig;
import com.sashafiesta.ccgraphics.CCGraphicsDataComponents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Fabric entry point. Registers the {@code graphics_disabled} data component and
 * binds the NightConfig-backed {@link CCGraphicsConfig} to the per-world
 * {@code serverconfig/} directory, falling back to the global config folder.
 */
public class CCGraphicsFabric implements ModInitializer {
    private static final LevelResource SERVERCONFIG = new LevelResource("serverconfig");

    @Override
    public void onInitialize() {
        CCGraphicsDataComponents.GRAPHICS_DISABLED = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            ResourceLocation.fromNamespaceAndPath("ccgraphics", "graphics_disabled"),
            CCGraphicsDataComponents.buildGraphicsDisabled()
        );

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            CCGraphicsConfig.load(
                server.getWorldPath(SERVERCONFIG).resolve("ccgraphics-server.toml"),
                FabricLoader.getInstance().getConfigDir().resolve("ccgraphics-server.toml")
            );
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CCGraphicsConfig.unload();
        });
    }
}
