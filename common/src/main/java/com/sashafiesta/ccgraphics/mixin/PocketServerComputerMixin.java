package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.GraphicsSync;
import dan200.computercraft.shared.pocket.core.PocketServerComputer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same broadcast marking as {@code ServerComputerMixin}, for the sends a pocket
 * computer makes after its super call - to everyone tracking it, and to whoever
 * is holding it. Those run once {@code ServerComputer.onTerminalChanged} has
 * already returned and dropped its own mark, hence the separate one here; the
 * counter in {@link GraphicsSync} keeps the two nested marks straight.
 * <p>
 * Deliberately not marked: the send in {@code tickServer} that catches players
 * who have just started tracking the computer. That one is a join, and the
 * snapshot default is exactly what it wants.
 */
@Mixin(value = PocketServerComputer.class, remap = false)
abstract class PocketServerComputerMixin {
    @Inject(method = "onTerminalChanged", at = @At("HEAD"))
    private void ccgraphics$beginBroadcast(CallbackInfo ci) {
        GraphicsSync.push();
    }

    @Inject(method = "onTerminalChanged", at = @At("RETURN"))
    private void ccgraphics$endBroadcast(CallbackInfo ci) {
        GraphicsSync.pop();
    }
}
