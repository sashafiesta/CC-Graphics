package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.GraphicsSync;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.shared.computer.terminal.TerminalState;
import dan200.computercraft.shared.network.client.MonitorClientMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity;
import dan200.computercraft.shared.peripheral.monitor.MonitorWatcher;
import dan200.computercraft.shared.peripheral.monitor.ServerMonitor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Monitor half of the broadcast/snapshot split. {@code onTick} is the per-tick
 * broadcast and is marked as such; {@code onWatch}, which fires when a player
 * starts tracking a chunk, is left unmarked so it takes the snapshot path.
 * <p>
 * Marking alone is not enough here, for two reasons. Monitors cache their
 * {@code TerminalState} on the block entity, so {@code onWatch} would happily
 * serve a joining player whatever {@code onTick} last cached - normally a diff
 * they have no base for. And before it even gets that far, {@code onWatch}
 * skips any monitor already queued for broadcast, sending such a player nothing
 * at all. The two injectors below close both holes.
 */
@Mixin(value = MonitorWatcher.class, remap = false)
abstract class MonitorWatcherMixin {
    @Inject(method = "onTick", at = @At("HEAD"))
    private static void ccgraphics$beginBroadcast(CallbackInfo ci) {
        // Runs unconditionally once per server tick, which makes it the natural
        // place to shed depth leaked by a broadcast that threw before its
        // RETURN hook.
        GraphicsSync.resetForTick();
        GraphicsSync.push();
    }

    @Inject(method = "onTick", at = @At("RETURN"))
    private static void ccgraphics$endBroadcast(CallbackInfo ci) {
        GraphicsSync.pop();
    }

    /**
     * Hand a joining player a self-contained frame directly, then report "no
     * monitor here" so the caller does not send one of its own.
     * <p>
     * {@code onWatch} skips any monitor already sitting on the broadcast queue,
     * and a graphics program redrawing every tick is put on that queue by
     * {@code MonitorBlockEntity.blockTick} earlier in the very same server tick
     * that ships the player their chunks - so the monitors this hook exists to
     * protect are exactly the ones it never used to see. Such a player is
     * skipped here and then swept up by that tick's broadcast instead, which is
     * a diff against a frame they have never held and which their client
     * discards for want of a keyframe. The monitor stays black until a keyframe
     * happens along; under a diff compressor with no timed keyframes, or a
     * program that has gone idle, that is never.
     * <p>
     * Taking over the send rather than merely suppressing the skip is what
     * keeps this from double-sending: whichever way the {@code enqueued} test
     * would have gone, exactly one packet leaves for this player.
     * <p>
     * The eligibility test mirrors {@code MonitorWatcher.getMonitor}, which is
     * private and so cannot be delegated to: only an origin monitor owns the
     * terminal, and a removed one owns nothing worth sending. Anything not in
     * graphics mode is handed straight back, leaving text monitors on CC:T's
     * shared cached state exactly as before.
     */
    @Redirect(
        method = "onWatch",
        at = @At(
            value = "INVOKE",
            target = "Ldan200/computercraft/shared/peripheral/monitor/MonitorWatcher;getMonitor(Ldan200/computercraft/shared/peripheral/monitor/MonitorBlockEntity;)Ldan200/computercraft/shared/peripheral/monitor/ServerMonitor;"
        )
    )
    private static @Nullable ServerMonitor ccgraphics$sendJoinFrame(MonitorBlockEntity tile, LevelChunk chunk, ServerPlayer player) {
        var serverMonitor = !tile.isRemoved() && tile.getXIndex() == 0 && tile.getYIndex() == 0
            ? tile.getCachedServerMonitor()
            : null;
        if (serverMonitor == null) return null;

        // Depth is never held here - chunk sending is not nested inside a
        // broadcast - but were it ever leaked, writing now would emit a diff and
        // advance the chain, stranding every existing viewer. Deferring to CC:T
        // costs this one player a frame; the alternative costs everybody.
        if (GraphicsSync.isBroadcasting()) return serverMonitor;

        var terminal = serverMonitor.getTerminal();
        if (terminal == null) return serverMonitor;
        if (((IGraphicsTerminal) terminal).ccgraphics$getGraphicsMode() <= 0) return serverMonitor;

        // Unmarked, so NetworkedTerminalMixin takes the snapshot path: a
        // keyframe of the live buffer that leaves the diff chain untouched.
        ServerNetworking.sendToPlayer(
            new MonitorClientMessage(tile.getBlockPos(), Optional.of(TerminalState.create(terminal))), player
        );
        return null;
    }

    /**
     * Build a fresh state rather than handing out the cached broadcast frame,
     * for any non-broadcast caller that still reaches here. The join path above
     * intercepts graphics monitors before this point, so this is now a backstop
     * against a graphics diff escaping {@code tile.cached} to a receiver with
     * nothing to apply it to. Deliberately not stored back into that cache,
     * which belongs to the broadcast chain.
     * <p>
     * Scoped to graphics mode so text monitors keep sharing one cached state
     * across every player loading a chunk, exactly as CC:T intends.
     */
    @Inject(method = "getState", at = @At("HEAD"), cancellable = true)
    private static void ccgraphics$freshStateForJoin(
        MonitorBlockEntity tile, ServerMonitor monitor, CallbackInfoReturnable<TerminalState> cir
    ) {
        if (GraphicsSync.isBroadcasting()) return;

        var terminal = monitor.getTerminal();
        if (terminal == null) return;
        if (((IGraphicsTerminal) terminal).ccgraphics$getGraphicsMode() <= 0) return;

        cir.setReturnValue(TerminalState.create(terminal));
    }
}
