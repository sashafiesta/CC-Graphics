package com.sashafiesta.ccgraphics.duck;

/**
 * Cleanup hook letting the in-GUI terminal widget release its graphics texture and
 * registered {@code ResourceLocation}. Implemented by {@code TerminalWidgetMixin}
 * and driven by {@code ScreenMixin}/{@code AbstractContainerScreenMixin}.
 * <p>
 * The owning screen has to do the driving: CC:T's {@code TerminalWidget} exposes no
 * lifecycle callback, and cleaning up from the render path cannot work because a
 * widget dropped by a screen close or {@code rebuildWidgets} is never rendered
 * again. Implementations must tolerate repeated calls - both hooks may fire for the
 * same widget - and calls before the
 * {@link com.sashafiesta.ccgraphics.client.GraphicsTexture} was ever allocated.
 */
public interface IGraphicsWidget {
    void ccgraphics$close();
}
