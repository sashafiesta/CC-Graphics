package com.sashafiesta.ccgraphics.mixin.client;

import com.sashafiesta.ccgraphics.client.GraphicsTexture;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import com.sashafiesta.ccgraphics.duck.IGraphicsWidget;
import dan200.computercraft.client.gui.widgets.TerminalWidget;
import dan200.computercraft.core.input.UserComputerInput;
import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side mixin on the in-GUI computer terminal widget. When the terminal
 * is in graphics mode, routes pixel-precise mouse events bypassing the
 * character-grid clamp, and overlays the per-widget
 * {@link com.sashafiesta.ccgraphics.client.GraphicsTexture} on top of the text
 * render at TAIL with a 2-pixel black margin.
 * <p>
 * Implements {@link IGraphicsWidget} so the owning screen can free that texture -
 * see {@link ScreenMixin}. The widget itself has no lifecycle callback, and the
 * render path cannot stand in for one: {@code renderWidget} bails out on
 * {@code !visible}, and a widget dropped on screen close or {@code rebuildWidgets}
 * is never rendered again at all.
 */
@Mixin(TerminalWidget.class)
abstract class TerminalWidgetMixin implements IGraphicsWidget {
    @Shadow(remap = false) @Final private Terminal terminal;
    @Shadow(remap = false) @Final private UserComputerInput computerInput;
    @Shadow(remap = false) @Final private int innerX;
    @Shadow(remap = false) @Final private int innerY;
    @Shadow(remap = false) @Final private int innerWidth;
    @Shadow(remap = false) @Final private int innerHeight;

    @Unique private final GraphicsTexture ccgraphics$texture = new GraphicsTexture("ccgfx_terminal");

    /**
     * {@link GraphicsTexture#close()} is a no-op once it has run, so this stays safe
     * when both screen hooks fire or when no texture was ever allocated.
     */
    @Override
    public void ccgraphics$close() {
        ccgraphics$texture.close();
    }

    @Unique
    private boolean ccgraphics$inGraphicsMode(double mouseX, double mouseY) {
        var gfx = (IGraphicsTerminal) terminal;
        return gfx.ccgraphics$getGraphicsMode() > 0
            && mouseX >= innerX && mouseY >= innerY
            && mouseX < innerX + innerWidth && mouseY < innerY + innerHeight;
    }

    @Unique
    private int ccgraphics$pixelX(double mouseX) {
        return Math.max(0, Math.min((int) (mouseX - innerX),
            ((IGraphicsTerminal) terminal).ccgraphics$getGraphicsWidth() - 1));
    }

    @Unique
    private int ccgraphics$pixelY(double mouseY) {
        return Math.max(0, Math.min((int) (mouseY - innerY),
            ((IGraphicsTerminal) terminal).ccgraphics$getGraphicsHeight() - 1));
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ccgraphics$inGraphicsMode(mouseX, mouseY)) {
            computerInput.mouseClick(button + 1, ccgraphics$pixelX(mouseX), ccgraphics$pixelY(mouseY));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onMouseReleased(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (ccgraphics$inGraphicsMode(mouseX, mouseY)) {
            computerInput.mouseUp(button + 1, ccgraphics$pixelX(mouseX), ccgraphics$pixelY(mouseY));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onMouseDragged(double mouseX, double mouseY, int button, double v2, double v3, CallbackInfoReturnable<Boolean> cir) {
        if (ccgraphics$inGraphicsMode(mouseX, mouseY)) {
            computerInput.mouseDrag(button + 1, ccgraphics$pixelX(mouseX), ccgraphics$pixelY(mouseY));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onMouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if (ccgraphics$inGraphicsMode(mouseX, mouseY) && deltaY != 0) {
            computerInput.mouseScroll(deltaY < 0 ? 1 : -1, ccgraphics$pixelX(mouseX), ccgraphics$pixelY(mouseY));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderWidget", at = @At("TAIL"))
    private void ccgraphics$onRenderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        var gfx = (IGraphicsTerminal) terminal;
        if (gfx.ccgraphics$getGraphicsMode() <= 0) {
            // Drop the buffer as soon as the computer leaves graphics mode rather than
            // holding it for the life of the screen. This is an optimisation only - the
            // guaranteed release is the screen's IGraphicsWidget hook.
            ccgraphics$close();
            return;
        }

        var textureLocation = ccgraphics$texture.update(terminal, true);

        graphics.bufferSource().endBatch();

        int marginColor = 0xFF000000;
        int margin = 2;
        graphics.fill(innerX - margin, innerY - margin, innerX + innerWidth + margin, innerY, marginColor);
        graphics.fill(innerX - margin, innerY + innerHeight, innerX + innerWidth + margin, innerY + innerHeight + margin, marginColor);
        graphics.fill(innerX - margin, innerY, innerX, innerY + innerHeight, marginColor);
        graphics.fill(innerX + innerWidth, innerY, innerX + innerWidth + margin, innerY + innerHeight, marginColor);

        graphics.blit(textureLocation, innerX, innerY, 0, 0.0f, 0.0f, innerWidth, innerHeight,
            ccgraphics$texture.getWidth(), ccgraphics$texture.getHeight());
    }
}
