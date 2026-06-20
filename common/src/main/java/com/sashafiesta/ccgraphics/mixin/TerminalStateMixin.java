package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.duck.IGraphicsTerminalState;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extends {@code TerminalState}'s wire format with the graphics payload (mode,
 * compressor id, byte array) plus the optional 256-color palette used in mode 2.
 * Written at the TAIL of {@code write(FriendlyByteBuf)} and read at the TAIL of
 * the {@code FriendlyByteBuf} constructor; the in-memory package-private
 * constructor leaves the fields at their {@code @Unique} defaults.
 */
@Mixin(value = TerminalState.class, remap = false)
abstract class TerminalStateMixin implements IGraphicsTerminalState {
    @Unique private int ccgraphics$graphicsMode = 0;
    @Unique private byte ccgraphics$compressionType = 0;
    @Unique private byte[] ccgraphics$graphicsData = new byte[0];
    @Unique private int[] ccgraphics$extPaletteData = null;

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void ccgraphics$readGraphics(FriendlyByteBuf buf, CallbackInfo ci) {
        // Tolerate senders without this addon: when the buffer ends right after
        // vanilla CC:T's payload there are no extension bytes to read, so leave
        // the fields at their defaults (mode 0, no graphics data, no ext palette).
        if (buf.readableBytes() <= 0) return;
        ccgraphics$graphicsMode = buf.readVarInt();
        if (ccgraphics$graphicsMode > 0) {
            ccgraphics$compressionType = buf.readByte();
            ccgraphics$graphicsData = buf.readByteArray();
        }
        if (ccgraphics$graphicsMode == 2) {
            var len = buf.readVarInt();
            if (len > 0) {
                ccgraphics$extPaletteData = new int[len];
                for (var i = 0; i < len; i++) {
                    ccgraphics$extPaletteData[i] = buf.readInt();
                }
            }
        }
    }

    @Inject(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void ccgraphics$writeGraphics(FriendlyByteBuf buf, CallbackInfo ci) {
        buf.writeVarInt(ccgraphics$graphicsMode);
        if (ccgraphics$graphicsMode > 0) {
            buf.writeByte(ccgraphics$compressionType);
            buf.writeByteArray(ccgraphics$graphicsData);
        }
        if (ccgraphics$graphicsMode == 2) {
            if (ccgraphics$extPaletteData != null) {
                buf.writeVarInt(ccgraphics$extPaletteData.length);
                for (var v : ccgraphics$extPaletteData) {
                    buf.writeInt(v);
                }
            } else {
                buf.writeVarInt(0);
            }
        }
    }

    @Override public int ccgraphics$getGraphicsMode() { return ccgraphics$graphicsMode; }
    @Override public byte ccgraphics$getGraphicsCompressionType() { return ccgraphics$compressionType; }
    @Override public byte[] ccgraphics$getGraphicsData() { return ccgraphics$graphicsData; }
    @Override public void ccgraphics$setGraphicsData(int mode, byte compressionType, byte[] data) {
        ccgraphics$graphicsMode = mode;
        ccgraphics$compressionType = compressionType;
        ccgraphics$graphicsData = data;
    }

    @Override
    public int[] ccgraphics$getExtPaletteData() { return ccgraphics$extPaletteData; }

    @Override
    public void ccgraphics$setExtPaletteData(int[] data) { ccgraphics$extPaletteData = data; }
}
