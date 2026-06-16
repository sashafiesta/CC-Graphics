package com.sashafiesta.ccgraphics.mixin.client;

import com.sashafiesta.ccgraphics.client.GraphicsTexture;
import com.sashafiesta.ccgraphics.client.IGraphicsMonitorRenderState;
import dan200.computercraft.client.render.monitor.MonitorRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches a per-monitor {@link GraphicsTexture} to CC:T's render state so it
 * shares the same lifetime as the rest of the monitor's GL resources - in
 * particular, {@code close()} runs when the client entity is unloaded.
 */
@Mixin(value = MonitorRenderState.class, remap = false)
abstract class MonitorRenderStateMixin implements IGraphicsMonitorRenderState {
    @Unique private GraphicsTexture ccgraphics$graphicsTexture;

    @Override
    public GraphicsTexture ccgraphics$getGraphicsTexture() {
        if (ccgraphics$graphicsTexture == null) {
            ccgraphics$graphicsTexture = new GraphicsTexture("ccgfx_monitor");
        }
        return ccgraphics$graphicsTexture;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void ccgraphics$onClose(CallbackInfo ci) {
        if (ccgraphics$graphicsTexture != null) {
            ccgraphics$graphicsTexture.close();
            ccgraphics$graphicsTexture = null;
        }
    }
}
