package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces a keyframe whenever {@code getTerminalState} is called outside the
 * per-tick broadcast - typically because a new viewer is joining and needs a
 * full frame to reconstruct against. The {@code inTick} flag distinguishes the
 * broadcast site from the new-viewer site.
 */
@Mixin(value = ServerComputer.class, remap = false)
abstract class ServerComputerMixin {
    @Shadow @Final private NetworkedTerminal terminal;

    @Unique private boolean ccgraphics$inTick = false;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void ccgraphics$tickStart(CallbackInfo ci) {
        ccgraphics$inTick = true;
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void ccgraphics$tickEnd(CallbackInfo ci) {
        ccgraphics$inTick = false;
    }

    @Inject(method = "getTerminalState", at = @At("HEAD"))
    private void ccgraphics$forceKeyframeForNewViewer(CallbackInfoReturnable<TerminalState> cir) {
        if (!ccgraphics$inTick) {
            ((IGraphicsTerminal) terminal).ccgraphics$requestKeyframe();
        }
    }
}
