package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.ClasspathMount;
import com.sashafiesta.ccgraphics.OverlayMount;
import dan200.computercraft.api.filesystem.Mount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wraps the ROM mount with an overlay so the patched ROM files always take
 * precedence over CC:T's, regardless of datapack/resource pack load order.
 */
@Mixin(targets = "dan200.computercraft.core.computer.ComputerExecutor", remap = false)
abstract class ComputerExecutorMixin {
    @Unique
    private static final ClasspathMount ccgraphics$overlay = new ClasspathMount("data/ccgraphics/lua/rom")
        .addFile("apis/term.lua")
        .addFile("apis/window.lua")
        .addFile("apis/paintutils.lua")
        .addFile("apis/peripheral.lua")
        .addFile("programs/shell.lua")
        .addFile("programs/clear.lua")
        .addFile("programs/fun/advanced/gfxpaint.lua")
        .addFile("programs/fun/advanced/pngview.lua")
        .addFile("programs/fun/advanced/raycast.lua")
        .addFile("help/term.txt")
        .addFile("help/clear.txt")
        .addFile("help/gfxpaint.txt")
        .addFile("help/pngview.txt")
        .addFile("help/raycast.txt");

    @Inject(method = "getRomMount", at = @At("RETURN"), cancellable = true)
    private void ccgraphics$wrapRomMount(CallbackInfoReturnable<Mount> cir) {
        var original = cir.getReturnValue();
        if (original != null) {
            cir.setReturnValue(new OverlayMount(original, ccgraphics$overlay));
        }
    }
}
