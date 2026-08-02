package com.sashafiesta.ccgraphics;

/**
 * Tracks whether the current {@code NetworkedTerminal.write()} is part of a
 * per-tick broadcast or a one-off snapshot for a receiver that holds no prior
 * frame.
 * <p>
 * Graphics frames are normally XOR diffs against the sender's previous frame,
 * so a receiver can only apply one if it already holds that frame. A terminal
 * keeps a single diff chain shared by all its viewers, which means anything
 * that is not the broadcast - opening a GUI, starting to track a monitor's
 * chunk, a pocket computer entering range - is talking to somebody who has
 * nothing to diff against and must be handed a self-contained keyframe.
 * <p>
 * The default is therefore "snapshot", and broadcasts opt in. Consumers that
 * never opt in still render correctly, just without the bandwidth saving -
 * which is what makes third-party users of {@code NetworkedTerminal} work
 * without knowing this class exists.
 * <p>
 * Every {@code write()} call site runs on the server thread (computer ticks,
 * monitor ticks, chunk watches, block interactions), so a plain static counter
 * is sufficient; Lua threads only ever mark the terminal changed, and clients
 * only read. A counter rather than a flag because
 * {@code PocketServerComputer.onTerminalChanged} calls its super, nesting two
 * deep.
 */
public final class GraphicsSync {
    private static int depth = 0;
    private static long broadcastId = 0;

    private GraphicsSync() {}

    public static boolean isBroadcasting() {
        return depth > 0;
    }

    /**
     * Identity of the broadcast scope currently open, so a terminal can tell
     * "another receiver in the send I already encoded for" from "a new send".
     * <p>
     * A broadcast performs one {@code write()} per receiver - {@code
     * ServerComputer.sendToAllInteracting} evaluates its packet factory inside
     * the per-player loop, and {@code PocketServerComputer.onTerminalChanged}
     * builds a second state after its super call - but every one of those
     * receivers must be handed the same frame. Terminals key their memoised
     * payload on this value; see {@code NetworkedTerminalMixin.ccgraphics$onWrite}.
     * <p>
     * Only meaningful while {@link #isBroadcasting()}. Monotonic, so an id is
     * never reused by a later scope; a {@code long} makes wraparound
     * unreachable rather than merely improbable.
     */
    public static long broadcastId() {
        return broadcastId;
    }

    public static void push() {
        // Nested marks - a pocket computer's send after its super call - are
        // part of the same logical broadcast and must keep the same id, so only
        // the outermost push opens a new scope.
        if (depth++ == 0) broadcastId++;
    }

    public static void pop() {
        // Clamped so a stray pop cannot drive the counter negative and wedge
        // isBroadcasting() permanently false.
        if (depth > 0) depth--;
    }

    /**
     * Drop any depth left behind by a broadcast that threw before its RETURN
     * hook ran. Called once per server tick from {@code MonitorWatcher.onTick},
     * which bounds the damage from such a leak to a single tick - during which
     * the worst case is a joining viewer getting a diff it cannot apply, and
     * the next broadcast repairs them anyway since the chain itself is never
     * corrupted.
     */
    public static void resetForTick() {
        // Retire the leaked scope's id along with its depth. A terminal that
        // memoised a payload inside it must not have that payload re-served by
        // the next push, which would re-send a frame from a previous tick
        // without advancing the chain.
        if (depth > 0) broadcastId++;
        depth = 0;
    }
}
