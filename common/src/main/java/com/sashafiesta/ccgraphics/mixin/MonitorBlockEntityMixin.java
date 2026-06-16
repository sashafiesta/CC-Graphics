package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import dan200.computercraft.shared.peripheral.monitor.ServerMonitor;
import dan200.computercraft.shared.peripheral.monitor.XYPair;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Replaces {@code monitor_touch}'s character-grid coordinates with 0-indexed
 * pixel coordinates when the monitor's terminal is in graphics mode, mirroring
 * how mouse_click coordinates are translated for in-GUI terminals.
 */
@Mixin(value = MonitorBlockEntity.class, remap = false)
abstract class MonitorBlockEntityMixin {
    @Shadow @Final private boolean advanced;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int xIndex;
    @Shadow private int yIndex;

    @Shadow public abstract Direction getDirection();
    @Shadow public abstract Direction getOrientation();
    @Shadow abstract ServerMonitor getServerMonitor();
    @Shadow abstract void eachComputer(Consumer<IComputerAccess> fun);

    @Inject(method = "monitorTouched", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onMonitorTouched(float xPos, float yPos, float zPos, CallbackInfo ci) {
        if (!advanced) return;

        var serverMonitor = getServerMonitor();
        if (serverMonitor == null) return;

        var terminal = serverMonitor.getTerminal();
        if (terminal == null) return;

        var gfx = (IGraphicsTerminal) terminal;
        if (gfx.ccgraphics$getGraphicsMode() <= 0) return;

        var pair = XYPair
            .of(xPos, yPos, zPos, getDirection(), getOrientation())
            .add(xIndex, height - yIndex - 1);

        if (pair.x() > width - MonitorBlockEntity.RENDER_BORDER
            || pair.y() > height - MonitorBlockEntity.RENDER_BORDER
            || pair.x() < MonitorBlockEntity.RENDER_BORDER
            || pair.y() < MonitorBlockEntity.RENDER_BORDER) {
            ci.cancel();
            return;
        }

        var pixelWidth = terminal.getWidth() * 6;
        var pixelHeight = terminal.getHeight() * 9;
        var activeWidth = width - (MonitorBlockEntity.RENDER_BORDER + MonitorBlockEntity.RENDER_MARGIN) * 2.0;
        var activeHeight = height - (MonitorBlockEntity.RENDER_BORDER + MonitorBlockEntity.RENDER_MARGIN) * 2.0;

        var xPixelPos = (int) Math.min(pixelWidth - 1, Math.max(0,
            (pair.x() - MonitorBlockEntity.RENDER_BORDER - MonitorBlockEntity.RENDER_MARGIN) * pixelWidth / activeWidth));
        var yPixelPos = (int) Math.min(pixelHeight - 1, Math.max(0,
            (pair.y() - MonitorBlockEntity.RENDER_BORDER - MonitorBlockEntity.RENDER_MARGIN) * pixelHeight / activeHeight));

        eachComputer(c -> c.queueEvent("monitor_touch", c.getAttachmentName(), xPixelPos, yPixelPos));
        ci.cancel();
    }
}
