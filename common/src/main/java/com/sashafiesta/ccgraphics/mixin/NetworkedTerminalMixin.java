package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.CCGraphicsConfig;
import com.sashafiesta.ccgraphics.compression.GraphicsCompressor;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminalState;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.computer.terminal.NetworkedTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

@Mixin(value = NetworkedTerminal.class, remap = false)
abstract class NetworkedTerminalMixin extends Terminal {
    private NetworkedTerminalMixin() { super(0, 0, false); }

    private static final int KEYFRAME_INTERVAL = 20;
    private static final double NANOS_PER_TICK = 50_000_000.0;

    @Unique private byte[] ccgraphics$previousGraphics;
    @Unique private int ccgraphics$framesSinceKeyframe = KEYFRAME_INTERVAL;

    @Unique private boolean ccgraphics$hasReceivedKeyframe = false;

    @Unique private double ccgraphics$bucketBytes = -1.0;
    @Unique private long ccgraphics$bucketLastUpdateNs;

    /**
     * Token-bucket gate over graphics broadcasts. Refills in real-time so the
     * sustained rate is TPS-independent. Returns true and deducts {@code bytes}
     * if the bucket can pay; false otherwise. Bypassed entirely when
     * throttling is disabled in config.
     */
    @Unique
    private boolean ccgraphics$bucketTrySpend(int bytes) {
        if (!CCGraphicsConfig.bandwidthThrottlingEnabled()) return true;

        var capacity = CCGraphicsConfig.bandwidthCapacityBytes();
        var refillRate = CCGraphicsConfig.bandwidthRefillBytesPerTick();

        var nowNs = System.nanoTime();
        if (ccgraphics$bucketBytes < 0.0) {
            // First call on this terminal - start the bucket full.
            ccgraphics$bucketBytes = capacity;
        } else {
            var elapsedNs = Math.max(0L, nowNs - ccgraphics$bucketLastUpdateNs);
            var refill = (double) refillRate * (double) elapsedNs / NANOS_PER_TICK;
            ccgraphics$bucketBytes = Math.min((double) capacity, ccgraphics$bucketBytes + refill);
        }
        ccgraphics$bucketLastUpdateNs = nowNs;

        if (ccgraphics$bucketBytes >= bytes) {
            ccgraphics$bucketBytes -= bytes;
            return true;
        }
        return false;
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void ccgraphics$onWrite(CallbackInfoReturnable<TerminalState> cir) {
        var state = cir.getReturnValue();
        var gfx = (IGraphicsTerminal) this;
        var gfxState = (IGraphicsTerminalState) state;
        var mode = gfx.ccgraphics$getGraphicsMode();

        if (mode == 0) {
            gfxState.ccgraphics$setGraphicsData(0, (byte) 0, new byte[0]);
            ccgraphics$previousGraphics = null;
            ccgraphics$framesSinceKeyframe = KEYFRAME_INTERVAL;
            return;
        }

        var current = gfx.ccgraphics$getGraphics();
        var compressor = GraphicsCompressor.defaultCompressor();
        var forceKeyframe = gfx.ccgraphics$consumeKeyframeRequest();

        byte[] payload;
        byte payloadType;
        boolean payloadIsKeyframe;

        var canDiff = !forceKeyframe
            && compressor.isDiff()
            && ccgraphics$previousGraphics != null
            && ccgraphics$previousGraphics.length == current.length
            && (!compressor.hasTimedKeyframes() || ccgraphics$framesSinceKeyframe < KEYFRAME_INTERVAL);

        if (canDiff) {
            var diff = new byte[current.length];
            for (var i = 0; i < current.length; i++) {
                diff[i] = (byte) (current[i] ^ ccgraphics$previousGraphics[i]);
            }
            var diffCompressed = compressor.compress(diff);
            var keyframeCompressor = GraphicsCompressor.forName("lz4");
            var keyframeCompressed = keyframeCompressor.compress(current);

            if (keyframeCompressed.length < diffCompressed.length) {
                payload = keyframeCompressed;
                payloadType = keyframeCompressor.typeId();
                payloadIsKeyframe = true;
            } else {
                payload = diffCompressed;
                payloadType = compressor.typeId();
                payloadIsKeyframe = false;
            }
        } else {
            var keyframeCompressor = compressor.isDiff()
                ? GraphicsCompressor.forName("lz4")
                : compressor;
            payload = keyframeCompressor.compress(current);
            payloadType = keyframeCompressor.typeId();
            payloadIsKeyframe = true;
        }

        // New-viewer keyframes bypass the bucket - the alternative is the viewer
        // staring at garbage until the bucket refills.
        if (forceKeyframe || ccgraphics$bucketTrySpend(payload.length)) {
            gfxState.ccgraphics$setGraphicsData(mode, payloadType, payload);
            ccgraphics$framesSinceKeyframe = payloadIsKeyframe ? 0 : ccgraphics$framesSinceKeyframe + 1;
            if (ccgraphics$previousGraphics == null || ccgraphics$previousGraphics.length != current.length) {
                ccgraphics$previousGraphics = new byte[current.length];
            }
            System.arraycopy(current, 0, ccgraphics$previousGraphics, 0, current.length);
        } else {
            // mode > 0 with an empty payload tells the receiver "keep the current
            // buffer" - the read path skips when data.length == 0. Leaving
            // previousGraphics and framesSinceKeyframe untouched means subsequent
            // diffs still reconstruct correctly against the last frame we sent.
            gfxState.ccgraphics$setGraphicsData(mode, (byte) 0, new byte[0]);
        }

        if (mode == 2) {
            gfxState.ccgraphics$setExtPaletteData(gfx.ccgraphics$getExtPaletteData());
        }
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void ccgraphics$onRead(TerminalState state, CallbackInfo ci) {
        var gfxState = (IGraphicsTerminalState) state;
        var gfx = (IGraphicsTerminal) this;
        var mode = gfxState.ccgraphics$getGraphicsMode();
        gfx.ccgraphics$setGraphicsMode(mode);

        if (mode > 0 && gfxState.ccgraphics$getGraphicsData().length > 0) {
            var compressor = GraphicsCompressor.forTypeId(gfxState.ccgraphics$getGraphicsCompressionType());
            var expectedSize = gfx.ccgraphics$getGraphicsWidth() * gfx.ccgraphics$getGraphicsHeight();

            if (compressor.isDiff()) {
                if (!ccgraphics$hasReceivedKeyframe) {
                    return;
                }
                var data = compressor.decompress(gfxState.ccgraphics$getGraphicsData(), expectedSize);
                var buf = gfx.ccgraphics$getGraphics();
                var len = Math.min(data.length, buf.length);
                for (var i = 0; i < len; i++) {
                    buf[i] ^= data[i];
                }
            } else {
                var data = compressor.decompress(gfxState.ccgraphics$getGraphicsData(), expectedSize);
                var buf = gfx.ccgraphics$getGraphics();
                System.arraycopy(data, 0, buf, 0, Math.min(data.length, buf.length));
                ccgraphics$hasReceivedKeyframe = true;
            }
        }

        if (mode == 0) {
            ccgraphics$hasReceivedKeyframe = false;
        }

        if (mode == 2) {
            var extPalette = gfxState.ccgraphics$getExtPaletteData();
            if (extPalette != null) {
                gfx.ccgraphics$setExtPaletteData(extPalette);
            }
        }
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void ccgraphics$onWriteNBT(CompoundTag nbt, CallbackInfoReturnable<CompoundTag> cir) {
        var gfx = (IGraphicsTerminal) this;
        nbt.putInt("ccgfx_mode", gfx.ccgraphics$getGraphicsMode());
        if (gfx.ccgraphics$getGraphicsMode() > 0) {
            var src = gfx.ccgraphics$getGraphics();
            nbt.putByteArray("ccgfx_data", Arrays.copyOf(src, src.length));
        }
        if (gfx.ccgraphics$getGraphicsMode() == 2) {
            nbt.putIntArray("ccgfx_ext_palette", gfx.ccgraphics$getExtPaletteData());
        }
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void ccgraphics$onReadNBT(CompoundTag nbt, CallbackInfo ci) {
        var gfx = (IGraphicsTerminal) this;
        if (nbt.contains("ccgfx_mode")) {
            gfx.ccgraphics$setGraphicsMode(nbt.getInt("ccgfx_mode"));
        }
        if (nbt.contains("ccgfx_data")) {
            var saved = nbt.getByteArray("ccgfx_data");
            var buf = gfx.ccgraphics$getGraphics();
            System.arraycopy(saved, 0, buf, 0, Math.min(saved.length, buf.length));
        }
        if (nbt.contains("ccgfx_ext_palette")) {
            gfx.ccgraphics$setExtPaletteData(nbt.getIntArray("ccgfx_ext_palette"));
        }
    }
}
