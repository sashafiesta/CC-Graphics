package com.sashafiesta.ccgraphics.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import com.sashafiesta.ccgraphics.client.GraphicsRenderTypes;
import com.sashafiesta.ccgraphics.client.IGraphicsMonitorRenderState;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.client.FrameInfo;
import dan200.computercraft.client.integration.ShaderMod;
import dan200.computercraft.client.render.monitor.MonitorBlockEntityRenderer;
import dan200.computercraft.client.render.monitor.MonitorRenderState;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import dan200.computercraft.shared.util.DirectionUtil;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the monitor's terminal is in graphics mode, replaces the text-geometry
 * render with a textured quad sourced from the per-monitor {@link
 * com.sashafiesta.ccgraphics.client.GraphicsTexture}. Cancels the original
 * render so the text pipeline doesn't run underneath.
 */
@Mixin(value = MonitorBlockEntityRenderer.class, remap = false)
abstract class MonitorBlockEntityRendererMixin {
    @Shadow private static long lastFrame;

    @Unique private static final float ccgraphics$MARGIN = (float) (MonitorBlockEntity.RENDER_MARGIN * 1.1);

    @Inject(
        method = "render(Ldan200/computercraft/shared/peripheral/monitor/MonitorBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void ccgraphics$onRender(
        MonitorBlockEntity monitor,
        float partialTicks,
        PoseStack transform,
        MultiBufferSource bufferSource,
        int lightmapCoord,
        int overlayLight,
        CallbackInfo ci
    ) {
        var originMonitor = monitor.getOriginClientMonitor();
        if (originMonitor == null) return;

        var terminal = originMonitor.getTerminal();
        if (terminal == null) return;

        var gfx = (IGraphicsTerminal) terminal;
        if (gfx.ccgraphics$getGraphicsMode() <= 0) return;

        if (ShaderMod.get().isRenderingShadowPass()) return;

        // Same per-frame multi-block dedup the original uses: a multi-block monitor
        // may have render() called once per component block per frame, but the
        // origin terminal should only be drawn once. Allow multiple draws of the
        // same component to support shaders that run a pass twice.
        var renderState = originMonitor.getRenderState(MonitorRenderState::new);
        var monitorPos = monitor.getBlockPos();
        var renderFrame = FrameInfo.getRenderFrame();
        if (renderState.lastRenderFrame == renderFrame && !monitorPos.equals(renderState.lastRenderPos)) {
            ci.cancel();
            return;
        }
        lastFrame = renderFrame;
        renderState.lastRenderFrame = renderFrame;
        renderState.lastRenderPos = monitorPos;

        var origin = originMonitor.getOrigin();
        var originPos = origin.getBlockPos();
        var dir = origin.getDirection();
        var front = origin.getFront();
        var yaw = dir.toYRot();
        var pitch = DirectionUtil.toPitchAngle(front);

        transform.pushPose();
        transform.translate(
            originPos.getX() - monitorPos.getX() + 0.5,
            originPos.getY() - monitorPos.getY() + 0.5,
            originPos.getZ() - monitorPos.getZ() + 0.5
        );
        transform.mulPose(Axis.YN.rotationDegrees(yaw));
        transform.mulPose(Axis.XP.rotationDegrees(pitch));
        transform.translate(
            -0.5 + MonitorBlockEntity.RENDER_BORDER + MonitorBlockEntity.RENDER_MARGIN,
            origin.getHeight() - 0.5 - (MonitorBlockEntity.RENDER_BORDER + MonitorBlockEntity.RENDER_MARGIN),
            0.5
        );

        var xSize = (float) (origin.getWidth() - 2.0 * (MonitorBlockEntity.RENDER_MARGIN + MonitorBlockEntity.RENDER_BORDER));
        var ySize = (float) (origin.getHeight() - 2.0 * (MonitorBlockEntity.RENDER_MARGIN + MonitorBlockEntity.RENDER_BORDER));

        var graphicsTexture = ((IGraphicsMonitorRenderState) renderState).ccgraphics$getGraphicsTexture();
        var redraw = originMonitor.pollTerminalChanged();
        var textureLocation = graphicsTexture.update(terminal, redraw);

        var matrix = transform.last().pose();
        var consumer = bufferSource.getBuffer(GraphicsRenderTypes.fullbright(textureLocation));
        var light = LightTexture.pack(15, 15);

        // Four black margin strips tiled around the texture, then the texture
        // itself. None of the five quads overlap, so we don't need a depth
        // bias - which would z-fight at large view distances anyway, since NDC
        // depth precision compresses faster than any fixed world-space bias
        // survives.
        //
        // Local frame: positive Y is up, so the inner area runs (0, 0) to
        // (xSize, -ySize), and the bezel surround extends MARGIN past each side.
        ccgraphics$drawBlackQuad(consumer, matrix, light, -ccgraphics$MARGIN, ccgraphics$MARGIN, xSize + ccgraphics$MARGIN, 0);
        ccgraphics$drawBlackQuad(consumer, matrix, light, -ccgraphics$MARGIN, -ySize, xSize + ccgraphics$MARGIN, -ySize - ccgraphics$MARGIN);
        ccgraphics$drawBlackQuad(consumer, matrix, light, -ccgraphics$MARGIN, 0, 0, -ySize);
        ccgraphics$drawBlackQuad(consumer, matrix, light, xSize, 0, xSize + ccgraphics$MARGIN, -ySize);

        // POSITION_COLOR_TEX_LIGHTMAP vertex layout: position, color, UV, light.
        // No overlay or normal attribute - the text render type's vertex format
        // doesn't include them, so omitting them keeps the vertex compact.
        consumer.addVertex(matrix, 0, 0, 0).setColor(0xFFFFFFFF).setUv(0, 0).setLight(light);
        consumer.addVertex(matrix, 0, -ySize, 0).setColor(0xFFFFFFFF).setUv(0, 1).setLight(light);
        consumer.addVertex(matrix, xSize, -ySize, 0).setColor(0xFFFFFFFF).setUv(1, 1).setLight(light);
        consumer.addVertex(matrix, xSize, 0, 0).setColor(0xFFFFFFFF).setUv(1, 0).setLight(light);

        transform.popPose();
        ci.cancel();
    }

    @Unique
    private static void ccgraphics$drawBlackQuad(
        VertexConsumer consumer, Matrix4f matrix, int light,
        float x1, float y1, float x2, float y2
    ) {
        consumer.addVertex(matrix, x1, y1, 0).setColor(0xFF000000).setUv(0, 0).setLight(light);
        consumer.addVertex(matrix, x1, y2, 0).setColor(0xFF000000).setUv(0, 0).setLight(light);
        consumer.addVertex(matrix, x2, y2, 0).setColor(0xFF000000).setUv(0, 0).setLight(light);
        consumer.addVertex(matrix, x2, y1, 0).setColor(0xFF000000).setUv(0, 0).setLight(light);
    }
}
