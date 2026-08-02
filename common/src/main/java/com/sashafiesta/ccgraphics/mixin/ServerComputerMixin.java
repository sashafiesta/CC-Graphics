package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.GraphicsSync;
import dan200.computercraft.shared.computer.core.ServerComputer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the per-tick terminal broadcast, letting {@code NetworkedTerminalMixin}
 * send a diff. Every other caller of {@code getTerminalState} - opening a
 * computer GUI, a pocket computer entering a player's tracking range - is a
 * receiver holding no prior frame, and falls through to the snapshot path.
 * <p>
 * The whole method is marked rather than the {@code getTerminalState} call
 * itself: that call sits inside a lambda handed to {@code sendToAllInteracting},
 * so javac emits it into a synthetic method that an injection point on
 * {@code onTerminalChanged} cannot reach.
 */
@Mixin(value = ServerComputer.class, remap = false)
abstract class ServerComputerMixin {
    @Inject(method = "onTerminalChanged", at = @At("HEAD"))
    private void ccgraphics$beginBroadcast(CallbackInfo ci) {
        GraphicsSync.push();
    }

    @Inject(method = "onTerminalChanged", at = @At("RETURN"))
    private void ccgraphics$endBroadcast(CallbackInfo ci) {
        GraphicsSync.pop();
    }
}
