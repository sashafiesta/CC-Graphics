package com.sashafiesta.ccgraphics.mixin.client;

import com.sashafiesta.ccgraphics.client.GraphicsTexture;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
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

@Mixin(TerminalWidget.class)
abstract class TerminalWidgetMixin {
    @Shadow(remap = false) @Final private Terminal terminal;
    @Shadow(remap = false) @Final private UserComputerInput computerInput;
    @Shadow(remap = false) @Final private int innerX;
    @Shadow(remap = false) @Final private int innerY;
    @Shadow(remap = false) @Final private int innerWidth;
    @Shadow(remap = false) @Final private int innerHeight;

    @Unique private final GraphicsTexture ccgraphics$texture = new GraphicsTexture("ccgfx_terminal");

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
            ccgraphics$close();
            return;
        }

        var textureLocation = ccgraphics$texture.update(terminal);

        graphics.bufferSource().endBatch();

        int marginColor = 0xFF000000;
        int margin = 2;
        graphics.fill(innerX - margin, innerY - margin, innerX + innerWidth + margin, innerY, marginColor);
        graphics.fill(innerX - margin, innerY + innerHeight, innerX + innerWidth + margin, innerY + innerHeight + margin, marginColor);
        graphics.fill(innerX - margin, innerY, innerX, innerY + innerHeight, marginColor);
        graphics.fill(innerX + innerWidth, innerY, innerX + innerWidth + margin, innerY + innerHeight, marginColor);

        graphics.blit(textureLocation, innerX, innerY, 0, 0.0f, 0.0f, innerWidth, innerHeight, ccgraphics$texture.getWidth(), ccgraphics$texture.getHeight());
    }

    @Unique
    private void ccgraphics$close() {
        ccgraphics$texture.close();
    }
}
