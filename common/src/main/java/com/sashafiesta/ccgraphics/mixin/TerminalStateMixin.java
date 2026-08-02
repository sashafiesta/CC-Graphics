package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.duck.IGraphicsTerminalState;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import io.netty.handler.codec.DecoderException;
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
 * <p>
 * The extension is framed as {@code magic:int, length:int, payload[length]} rather than
 * appended bare. {@code TerminalState.STREAM_CODEC} is a plain {@code StreamCodec.ofMember},
 * so both halves run directly against the caller's shared buffer with no length prefix and
 * no slice of their own. In {@code ComputerContainerData} the terminal is field 2 of 4 and
 * is followed by an {@code ItemStack} and a var-int, so "the buffer ended" cannot mean "the
 * sender has no extension" - there are always more bytes there. Only an explicit marker can
 * tell the two apart, and the length prefix then bounds every subsequent read to the
 * extension so a truncated or hostile buffer cannot reach into the fields that follow.
 */
@Mixin(value = TerminalState.class, remap = false)
abstract class TerminalStateMixin implements IGraphicsTerminalState {
    /**
     * Marks the start of the graphics extension. The leading byte is {@code 0xCC}, which has
     * the var-int continuation bit set, so it cannot collide with the small item-count var-int
     * that {@code ItemStack.OPTIONAL_STREAM_CODEC} writes immediately after the terminal in
     * {@code ComputerContainerData} - the one place a vanilla sender puts readable bytes right
     * where the probe looks. That only narrows the odds, it does not close them: a sender
     * without this addon whose next four bytes happen to spell this value, and whose four
     * bytes after that happen to form an in-range length, is still misread. The residual risk
     * is roughly 1 in 2^32 per terminal packet and cannot be driven to zero without a
     * connection-level handshake, which a mixin-only addon has no place to hang.
     */
    @Unique private static final int ccgraphics$EXTENSION_MAGIC = 0xCC674758;

    /** Magic plus length prefix: both must be present before the probe can decide anything. */
    @Unique private static final int ccgraphics$HEADER_BYTES = 2 * Integer.BYTES;

    @Unique private int ccgraphics$graphicsMode = 0;
    @Unique private byte ccgraphics$compressionType = 0;
    @Unique private byte[] ccgraphics$graphicsData = new byte[0];
    @Unique private int[] ccgraphics$extPaletteData = null;

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void ccgraphics$readGraphics(FriendlyByteBuf buf, CallbackInfo ci) {
        // Probe for the marker without committing. This runs mid-stream, so when the sender
        // does not have this addon the reader index has to be left exactly where vanilla CC:T
        // put it - consuming even one byte makes the following ItemStack decode from our data
        // and kills the connection instead of opening the GUI.
        buf.markReaderIndex();
        if (buf.readableBytes() < ccgraphics$HEADER_BYTES || buf.readInt() != ccgraphics$EXTENSION_MAGIC) {
            buf.resetReaderIndex();
            return;
        }

        var length = buf.readInt();
        // Rejecting 0 as well as negatives: our writer always emits at least the mode var-int,
        // so an empty extension is provably not ours. Accepting it would let a magic collision
        // reach readVarInt on an empty slice and throw out of netty rather than rewinding.
        if (length < 1 || length > buf.readableBytes()) {
            // Our writer never emits an out-of-range length, so these bytes are not ours: either
            // the marker collision above or a corrupt buffer. Rewind and let the enclosing codec
            // fail on its own terms rather than swallowing bytes that belong to another field.
            buf.resetReaderIndex();
            return;
        }

        // Reading through a slice is what makes the framing load-bearing: readByteArray() caps
        // itself at the *enclosing* buffer's readableBytes, so read straight off `buf` an
        // overlong array would quietly swallow the fields that follow the terminal, whereas off
        // the slice it throws. Leftover slice bytes are ignored, so a later version of this
        // addon can append fields and still be read by this one.
        var ext = new FriendlyByteBuf(buf.readSlice(length));
        ccgraphics$graphicsMode = ext.readVarInt();
        if (ccgraphics$graphicsMode > 0) {
            ccgraphics$compressionType = ext.readByte();
            ccgraphics$graphicsData = ext.readByteArray();
        }
        // Read for any graphics mode, not just 2: pixel indices >= 16 survive a mode change,
        // and the client resolves them through this palette regardless of mode, so a mode-1
        // frame that inherited 256-colour pixels still needs it. The readableBytes guard keeps
        // an older sender - which only ever emitted the palette for mode 2 - readable here
        // rather than running the var-int off the end of the slice.
        if (ccgraphics$graphicsMode > 0 && ext.readableBytes() > 0) {
            var len = ext.readVarInt();
            // Bound the allocation by what the slice could actually hold, so a bogus count
            // cannot turn into a multi-gigabyte int[] before the first read fails.
            if (len < 0 || len > ext.readableBytes() / Integer.BYTES) {
                throw new DecoderException("Graphics palette with size " + len + " is bigger than allowed");
            }
            if (len > 0) {
                ccgraphics$extPaletteData = new int[len];
                for (var i = 0; i < len; i++) {
                    ccgraphics$extPaletteData[i] = ext.readInt();
                }
            }
        }
    }

    @Inject(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("TAIL"))
    private void ccgraphics$writeGraphics(FriendlyByteBuf buf, CallbackInfo ci) {
        // Emitted unconditionally, mode 0 included, so the marker is the one thing the reader
        // can rely on: a state that skipped it would be indistinguishable from a vanilla sender.
        buf.writeInt(ccgraphics$EXTENSION_MAGIC);
        // Fixed-width rather than a var-int so it can be back-patched in place once the payload
        // is written, which avoids staging the (potentially large) frame in a scratch buffer.
        var lengthIndex = buf.writerIndex();
        buf.writeInt(0);

        buf.writeVarInt(ccgraphics$graphicsMode);
        if (ccgraphics$graphicsMode > 0) {
            buf.writeByte(ccgraphics$compressionType);
            buf.writeByteArray(ccgraphics$graphicsData);
        }
        // Paired with the widened gate in ccgraphics$readGraphics - these two must move
        // together. Widening only the reader makes it run off the end of a mode-1 slice a
        // narrower writer produced, and throw on every such packet.
        if (ccgraphics$graphicsMode > 0) {
            if (ccgraphics$extPaletteData != null) {
                buf.writeVarInt(ccgraphics$extPaletteData.length);
                for (var v : ccgraphics$extPaletteData) {
                    buf.writeInt(v);
                }
            } else {
                buf.writeVarInt(0);
            }
        }

        buf.setInt(lengthIndex, buf.writerIndex() - lengthIndex - Integer.BYTES);
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
