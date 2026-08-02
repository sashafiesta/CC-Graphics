package com.sashafiesta.ccgraphics.mixin.client;

import com.sashafiesta.ccgraphics.duck.IGraphicsWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Companion to {@link ScreenMixin}, and the hook that actually matters in practice.
 * <p>
 * Both screens that embed a CC:T {@code TerminalWidget} on screen - CC:T's
 * {@code AbstractComputerScreen} and CC:Terminals' {@code TerminalScreen} - descend
 * from {@code AbstractContainerScreen}, whose {@code removed} override does <em>not</em>
 * call {@code super.removed()}. The {@code Screen.removed} injection therefore never
 * runs for them, and without this mixin their graphics texture would only ever be
 * freed on a resize.
 * <p>
 * HEAD rather than TAIL because {@code removed} returns early when the player is gone.
 * <p>
 * Vanilla target, so this mixin remaps.
 */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {
    @Inject(method = "removed", at = @At("HEAD"))
    private void ccgraphics$onRemoved(CallbackInfo ci) {
        // children() is inherited from Screen rather than declared here, so reach it
        // through the target type instead of @Shadow.
        for (var child : ((Screen) (Object) this).children()) {
            if (child instanceof IGraphicsWidget widget) widget.ccgraphics$close();
        }
    }
}
