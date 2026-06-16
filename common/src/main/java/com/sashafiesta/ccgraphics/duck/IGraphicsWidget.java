package com.sashafiesta.ccgraphics.duck;

/**
 * Cleanup hook intended for the in-GUI terminal widget to release its graphics
 * texture and registered {@code ResourceLocation} on Screen close. Currently
 * unused - {@code TerminalWidgetMixin} manages this state internally via
 * {@link com.sashafiesta.ccgraphics.client.GraphicsTexture}.
 */
public interface IGraphicsWidget {
    void ccgraphics$close();
}
