package com.sashafiesta.ccgraphics.client;

/**
 * Duck type for the per-monitor render state. The mixin on
 * {@code MonitorRenderState} owns one {@link GraphicsTexture} for that monitor's
 * lifetime and tears it down with the rest of the state's GL resources.
 */
public interface IGraphicsMonitorRenderState {
    GraphicsTexture ccgraphics$getGraphicsTexture();
}
