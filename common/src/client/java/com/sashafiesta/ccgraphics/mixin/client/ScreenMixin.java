package com.sashafiesta.ccgraphics.mixin.client;

import com.sashafiesta.ccgraphics.duck.IGraphicsWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Gives the in-GUI terminal widget a real teardown point, mirroring what
 * {@link MonitorRenderStateMixin} does for monitors.
 * <p>
 * CC:T's {@code TerminalWidget} has no lifecycle callback of its own, so nothing
 * else can free its {@code GraphicsTexture}: a discarded widget is simply never
 * rendered again, and the texture stays registered with the {@code TextureManager}
 * forever because {@code TextureManager.register(String, DynamicTexture)} mints a
 * fresh path per call and never displaces the previous entry.
 * <p>
 * Vanilla target, so this mixin remaps - unlike the CC:T targets elsewhere in this
 * addon, which are compiled against unobfuscated names and use {@code remap = false}.
 */
@Mixin(Screen.class)
abstract class ScreenMixin {
    @Shadow public abstract List<? extends GuiEventListener> children();

    /**
     * Screens holding no graphics widget pay only an {@code instanceof} per child, so
     * this is cheap enough to run for every screen in the game.
     */
    @Unique
    private void ccgraphics$closeGraphicsWidgets() {
        for (var child : children()) {
            if (child instanceof IGraphicsWidget widget) widget.ccgraphics$close();
        }
    }

    /**
     * Covers screens that inherit this method - notably CC:T's
     * {@code NoTermComputerScreen}, which extends {@code Screen} directly. Container
     * screens override {@code removed} without a super call and are handled by
     * {@link AbstractContainerScreenMixin} instead.
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void ccgraphics$onRemoved(CallbackInfo ci) {
        ccgraphics$closeGraphicsWidgets();
    }

    /**
     * {@code rebuildWidgets} routes through here, so this catches window resizes and
     * GUI-scale changes, which throw the widget away without ever closing the screen.
     * Must be HEAD: {@code clearWidgets} empties the children list.
     */
    @Inject(method = "clearWidgets", at = @At("HEAD"))
    private void ccgraphics$onClearWidgets(CallbackInfo ci) {
        ccgraphics$closeGraphicsWidgets();
    }
}
