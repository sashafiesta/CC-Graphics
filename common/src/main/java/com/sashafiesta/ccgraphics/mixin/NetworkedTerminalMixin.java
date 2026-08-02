package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.CCGraphicsConfig;
import com.sashafiesta.ccgraphics.GraphicsSync;
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

/**
 * Declares {@link IGraphicsTerminal} so it can override
 * {@code ccgraphics$invalidateGraphicsSync}, whose no-op base implementation
 * {@code TerminalMixin} puts on {@code Terminal}. Every other interface method
 * is inherited from there at runtime, which is why this mixin only defines the
 * one it actually specialises.
 */
@Mixin(value = NetworkedTerminal.class, remap = false)
abstract class NetworkedTerminalMixin extends Terminal implements IGraphicsTerminal {
    private NetworkedTerminalMixin() { super(0, 0, false); }

    private static final int KEYFRAME_INTERVAL = 20;
    private static final double NANOS_PER_TICK = 50_000_000.0;

    @Unique private byte[] ccgraphics$previousGraphics;
    @Unique private int ccgraphics$framesSinceKeyframe = KEYFRAME_INTERVAL;

    /**
     * The frame already encoded for the broadcast scope named by
     * {@code ccgraphics$broadcastFrameId}, re-served byte for byte to every
     * later receiver in that same scope.
     * <p>
     * One broadcast performs one {@code write()} per receiver, and the chain may
     * only advance once per broadcast: the second write would otherwise diff the
     * live buffer against the copy the first write just stored, ship the
     * resulting all-zero frame, and freeze that receiver on the previous image
     * for good under a compressor with no timed keyframes. An all-zero buffer is
     * also maximally compressible, so the "keyframe is smaller" escape can never
     * rescue it.
     * <p>
     * Mode and palette travel with the payload so a receiver cannot be handed a
     * frame described by a different mode or coloured by a different palette
     * than its neighbour in the same send.
     */
    @Unique private long ccgraphics$broadcastFrameId = 0L;
    @Unique private byte[] ccgraphics$broadcastFrame;
    @Unique private byte ccgraphics$broadcastFrameType;
    @Unique private int ccgraphics$broadcastFrameMode;
    @Unique private int[] ccgraphics$broadcastFramePalette;

    /**
     * Compressed keyframe of the diff chain's base, reused across snapshots.
     * Only ever served when the base is byte-identical to the live buffer, so a
     * missed invalidation cannot leak a stale frame - see
     * {@code ccgraphics$writeSnapshot}.
     */
    @Unique private byte[] ccgraphics$snapshotPayload;
    @Unique private byte ccgraphics$snapshotType;

    /**
     * Set when a snapshot handed somebody a frame that is not the chain base,
     * leaving them unable to apply the next diff. Forces that broadcast to be a
     * keyframe so every viewer, old and new, resyncs on one frame.
     */
    @Unique private boolean ccgraphics$broadcastKeyframeNeeded = false;

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

    /**
     * Whether a payload the bucket has just refused could ever be paid for.
     * <p>
     * A refusal re-arms the change flag so the frame is retried, but a frame
     * larger than the bucket can ever hold - or any frame at all once refill is
     * configured to zero - would then mark the terminal changed every tick for
     * the rest of the world's life while still never sending anything. Such a
     * frame is dropped instead, exactly as it was before the retry existed.
     */
    @Unique
    private static boolean ccgraphics$bucketCanEventuallyPay(int bytes) {
        if (!CCGraphicsConfig.bandwidthThrottlingEnabled()) return true;
        return CCGraphicsConfig.bandwidthRefillBytesPerTick() > 0L
            && CCGraphicsConfig.bandwidthCapacityBytes() >= bytes;
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
            ccgraphics$snapshotPayload = null;
            ccgraphics$broadcastFrame = null;
            ccgraphics$broadcastFramePalette = null;
            ccgraphics$broadcastKeyframeNeeded = false;
            return;
        }

        var current = gfx.ccgraphics$getGraphics();

        if (!GraphicsSync.isBroadcasting()) {
            ccgraphics$writeSnapshot(gfx, gfxState, mode, current);
            return;
        }

        if (ccgraphics$broadcastFrame != null && ccgraphics$broadcastFrameId == GraphicsSync.broadcastId()) {
            // Another receiver in the send this frame was already encoded for.
            // Re-serve it verbatim: the chain, the keyframe counter, the bucket
            // and the pending-keyframe flag all belong to the frame, not to the
            // receiver, and must move exactly once per broadcast. Receivers who
            // are not on the chain never reach here - they take the snapshot
            // path, which runs outside any broadcast scope.
            gfxState.ccgraphics$setGraphicsData(
                ccgraphics$broadcastFrameMode, ccgraphics$broadcastFrameType, ccgraphics$broadcastFrame);
            if (ccgraphics$broadcastFramePalette != null) {
                gfxState.ccgraphics$setExtPaletteData(ccgraphics$broadcastFramePalette);
            }
            return;
        }

        var compressor = GraphicsCompressor.defaultCompressor();

        byte[] payload;
        byte payloadType;
        boolean payloadIsKeyframe;

        var canDiff = !ccgraphics$broadcastKeyframeNeeded
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

        byte[] sentPayload;
        byte sentType;

        if (ccgraphics$bucketTrySpend(payload.length)) {
            gfxState.ccgraphics$setGraphicsData(mode, payloadType, payload);
            sentPayload = payload;
            sentType = payloadType;
            ccgraphics$framesSinceKeyframe = payloadIsKeyframe ? 0 : ccgraphics$framesSinceKeyframe + 1;
            // A keyframe puts every viewer on one frame, which is exactly what an
            // off-chain snapshot receiver was waiting for.
            if (payloadIsKeyframe) ccgraphics$broadcastKeyframeNeeded = false;
            if (ccgraphics$previousGraphics == null || ccgraphics$previousGraphics.length != current.length) {
                ccgraphics$previousGraphics = new byte[current.length];
            }
            System.arraycopy(current, 0, ccgraphics$previousGraphics, 0, current.length);
            // The chain base just moved; any cached keyframe of it is spent.
            ccgraphics$snapshotPayload = null;
        } else {
            // mode > 0 with an empty payload tells the receiver "keep the current
            // buffer" - the read path skips when data.length == 0. Leaving
            // previousGraphics and framesSinceKeyframe untouched means subsequent
            // diffs still reconstruct correctly against the last frame we sent.
            var denied = new byte[0];
            gfxState.ccgraphics$setGraphicsData(mode, (byte) 0, denied);
            sentPayload = denied;
            sentType = (byte) 0;

            // Every broadcast driver is edge-triggered off a flag it clears
            // before sending - ServerComputer.tickServer, MonitorBlockEntity's
            // pollTerminalChanged, CC:Terminals' serverTick - so a frame dropped
            // here is never re-offered. Were it the last frame a program drew,
            // existing viewers would hold the frame before it forever while any
            // later joiner, served a live snapshot, saw the real one. Re-arming
            // the flag retries next tick, mirroring how CC:T's own
            // monitorBandwidth limiter leaves unprocessed monitors enqueued.
            //
            // Cannot recurse: every changed callback in play only sets a flag or
            // schedules a block tick, none of them re-enter write().
            if (ccgraphics$bucketCanEventuallyPay(payload.length)) {
                ((Terminal) (Object) this).setChanged();
            }
        }

        // Sent for every graphics mode, not just mode 2: pixel indices >= 16
        // survive a mode change and the client resolves them through the
        // extended palette regardless of mode, so withholding it leaves viewers
        // disagreeing about the colours of the very same buffer.
        var palette = gfx.ccgraphics$getExtPaletteData();
        gfxState.ccgraphics$setExtPaletteData(palette);

        ccgraphics$broadcastFrameId = GraphicsSync.broadcastId();
        ccgraphics$broadcastFrame = sentPayload;
        ccgraphics$broadcastFrameType = sentType;
        ccgraphics$broadcastFrameMode = mode;
        ccgraphics$broadcastFramePalette = palette;
    }

    /**
     * Build a self-contained frame for a receiver that holds nothing to diff
     * against - a player opening a GUI, or one that has just started tracking a
     * monitor's chunk.
     * <p>
     * Always sends what the terminal currently shows. Sending the diff chain's
     * base instead would be cheaper to cache, but the base only advances on a
     * broadcast and broadcasts only happen while somebody is watching: content
     * drawn to an unwatched terminal leaves the base arbitrarily far behind, and
     * a receiver handed that stale frame would keep it until the next change -
     * forever, if the program has gone idle.
     * <p>
     * When the base does match the live buffer byte for byte the receiver lands
     * on the chain for free and the cached keyframe is reused. Otherwise it is
     * off-chain and cannot apply the next diff, so the next broadcast is forced
     * to a keyframe, resyncing old and new viewers together. Either way the
     * chain itself is left untouched: advancing it here would strand every
     * existing viewer, none of whom see this payload.
     * <p>
     * Never metered by the bandwidth bucket - a throttled snapshot is a viewer
     * staring at garbage until the bucket refills.
     */
    @Unique
    private void ccgraphics$writeSnapshot(IGraphicsTerminal gfx, IGraphicsTerminalState gfxState, int mode, byte[] current) {
        var encoder = ccgraphics$snapshotEncoder();
        var base = ccgraphics$previousGraphics;

        if (base != null && base.length == current.length && Arrays.equals(base, current)) {
            // Equality is what licenses the cache: the payload can only ever be
            // served while it still describes the live buffer, so a missed
            // invalidation degrades to a wasted comparison rather than a stale
            // frame. memcmp is far cheaper than the compression it saves.
            if (ccgraphics$snapshotPayload == null) {
                ccgraphics$snapshotPayload = encoder.compress(base);
                ccgraphics$snapshotType = encoder.typeId();
            }
            gfxState.ccgraphics$setGraphicsData(mode, ccgraphics$snapshotType, ccgraphics$snapshotPayload);
        } else {
            // Uncached: the live buffer can change under the Lua thread between
            // calls, so nothing here stays valid long enough to keep.
            gfxState.ccgraphics$setGraphicsData(mode, encoder.typeId(), encoder.compress(current));
            ccgraphics$broadcastKeyframeNeeded = true;
        }

        // Every graphics mode, not just mode 2 - see the matching note in
        // ccgraphics$onWrite. A joiner that is handed the buffer but not the
        // palette it was painted with is the split this fixes.
        gfxState.ccgraphics$setExtPaletteData(gfx.ccgraphics$getExtPaletteData());
    }

    /**
     * Encoder for a self-contained frame. A diff compressor cannot produce one -
     * the receiver has nothing to XOR against - so those fall back to plain lz4;
     * raw, lz4 and delta_lz4 already emit standalone frames and are used as
     * configured. The chosen type id travels with the payload, so a compressor
     * swapped in config after a payload was cached still decodes correctly.
     */
    @Unique
    private static GraphicsCompressor ccgraphics$snapshotEncoder() {
        var compressor = GraphicsCompressor.defaultCompressor();
        return compressor.isDiff() ? GraphicsCompressor.forName("lz4") : compressor;
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

        if (mode > 0) {
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
            // The palette goes with the buffer in every graphics mode, not just
            // mode 2 - see the note in ccgraphics$onWrite. Pixel indices >= 16
            // outlive the mode that painted them, so a mode-1 terminal reloaded
            // without its palette comes back a different picture than it went
            // down as.
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
            // Length-checked here rather than trusted to the setter: this tag is
            // player-writable through /data merge block, and the boundary that
            // accepts it is the right place to say what shape it must have.
            var extPalette = nbt.getIntArray("ccgfx_ext_palette");
            if (extPalette.length == EXT_PALETTE_SIZE) {
                gfx.ccgraphics$setExtPaletteData(extPalette);
            }
        }
    }

    /**
     * Drop every piece of sync state the old pixel buffer made valid.
     * <p>
     * A resize reallocates the buffer on both sides, so the chain base, the
     * cached keyframe and the receiver's "I hold a keyframe" flag all describe
     * an image that no longer exists. The length check guarding the diff path
     * does not catch an area-preserving reshape - a monitor going 18x26 to
     * 39x12 keeps all 468 cells - and when the last frame was a uniform colour
     * the diff is uniform too, the compressed lengths tie, the strict
     * {@code <} keeps the diff, and it lands on a base the receiver never had.
     * <p>
     * {@code ServerMonitor.rebuild} reuses the same {@code NetworkedTerminal}
     * and the client keeps its {@code ClientMonitor}, so none of this state is
     * discarded for us.
     */
    @Override
    public void ccgraphics$invalidateGraphicsSync() {
        ccgraphics$previousGraphics = null;
        ccgraphics$framesSinceKeyframe = KEYFRAME_INTERVAL;
        ccgraphics$snapshotPayload = null;
        ccgraphics$broadcastFrame = null;
        ccgraphics$broadcastFramePalette = null;
        ccgraphics$hasReceivedKeyframe = false;
    }
}
