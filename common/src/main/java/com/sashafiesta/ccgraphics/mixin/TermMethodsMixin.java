package com.sashafiesta.ccgraphics.mixin;

import com.sashafiesta.ccgraphics.CCGraphicsConfig;
import com.sashafiesta.ccgraphics.duck.IGraphicsTerminal;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.core.apis.TermMethods;
import dan200.computercraft.core.terminal.Palette;
import dan200.computercraft.core.terminal.Terminal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(value = TermMethods.class, remap = false)
abstract class TermMethodsMixin {
    @Shadow
    public abstract Terminal getTerminal() throws LuaException;

    @Unique
    private IGraphicsTerminal ccgraphics$gfx() throws LuaException {
        return (IGraphicsTerminal) getTerminal();
    }

    @Unique
    private static LuaException ccgraphics$badArg(int arg, String message) {
        return new LuaException("bad argument #" + arg + " (" + message + ")");
    }

    @Unique
    private static LuaException ccgraphics$badArgType(int arg, String expected, Object actual) {
        String typeName;
        if (actual == null) typeName = "nil";
        else if (actual instanceof Boolean) typeName = "boolean";
        else if (actual instanceof Number) typeName = "number";
        else if (actual instanceof String) typeName = "string";
        else if (actual instanceof Map) typeName = "table";
        else typeName = actual.getClass().getSimpleName();
        return new LuaException("bad argument #" + arg + " (expected " + expected + ", got " + typeName + ")");
    }

    @Unique
    private static int ccgraphics$colorArgToIndex(int arg, int luaColor) throws LuaException {
        if (luaColor <= 0 || luaColor > 0x8000) throw ccgraphics$badArg(arg, "invalid color " + luaColor);
        var bitIndex = 31 - Integer.numberOfLeadingZeros(luaColor);
        if (bitIndex < 0 || bitIndex > 15) throw ccgraphics$badArg(arg, "invalid color " + luaColor);
        return bitIndex;
    }

    @Unique
    private static int ccgraphics$log2i(int n) {
        if (n <= 1) return 0;
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    @Unique
    private static int ccgraphics$indexToColorArg(int index) {
        return 1 << index;
    }

    @LuaFunction
    public final void setGraphicsMode(IArguments args) throws LuaException {
        var value = args.get(0);
        int mode;
        if (value instanceof Boolean b) { mode = b ? 1 : 0; }
        else if (value instanceof Number n) { mode = n.intValue(); }
        else { throw ccgraphics$badArgType(1, "boolean or number", value); }
        if (mode != 0 && mode != 1 && mode != 2) throw ccgraphics$badArg(1, "invalid mode " + mode);
        var terminal = getTerminal();
        if (!CCGraphicsConfig.allowGrayscaleGraphics() && !terminal.isColour() && mode != 0)
            throw new LuaException("Graphics mode is not available on this computer");
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            if (mode != 0 && gfx.ccgraphics$isGraphicsDisabled()) throw new LuaException("Graphics mode is disabled on this computer");
            gfx.ccgraphics$setGraphicsMode(mode);
        }
    }

    @LuaFunction
    public final Object getGraphicsMode() throws LuaException {
        var mode = ccgraphics$gfx().ccgraphics$getGraphicsMode();
        return mode == 0 ? false : mode;
    }

    @LuaFunction
    public final void setPixel(IArguments args) throws LuaException {
        var x = args.getInt(0);
        var y = args.getInt(1);
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            var mode = gfx.ccgraphics$getGraphicsMode();
            var rawColor = args.getInt(2);
            int colorIndex;
            if (mode == 2) {
                colorIndex = rawColor;
            } else {
                colorIndex = rawColor > 0 ? 31 - Integer.numberOfLeadingZeros(rawColor) : -1;
            }
            var gw = gfx.ccgraphics$getGraphicsWidth();
            var gh = gfx.ccgraphics$getGraphicsHeight();
            if (x < 0 || x >= gw || y < 0 || y >= gh) return;
            var maxIndex = mode == 2 ? 255 : 15;
            if (colorIndex < 0 || colorIndex > maxIndex) throw ccgraphics$badArg(3, "invalid color " + rawColor);
            gfx.ccgraphics$setPixel(x, y, colorIndex);
        }
    }

    @LuaFunction
    public final Object[] getPixel(IArguments args) throws LuaException {
        var x = args.getInt(0);
        var y = args.getInt(1);
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            var mode = gfx.ccgraphics$getGraphicsMode();
            var index = gfx.ccgraphics$getPixel(x, y);
            if (index < 0) return new Object[]{ null };
            if (mode == 0) return new Object[0];
            if (mode == 2) return new Object[]{ index };
            return new Object[]{ ccgraphics$indexToColorArg(index) };
        }
    }

    @LuaFunction
    public final void drawPixels(IArguments args) throws LuaException {
        var x = args.getInt(0);
        var y = args.getInt(1);
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            if (x >= gfx.ccgraphics$getGraphicsWidth() || y >= gfx.ccgraphics$getGraphicsHeight()) return;
            var mode = gfx.ccgraphics$getGraphicsMode();
            var third = args.get(2);
            if (third instanceof Number) {
                var w = args.getInt(3);
                var h = args.getInt(4);
                if (w < 0) throw ccgraphics$badArg(4, "width cannot be negative");
                if (h < 0) throw ccgraphics$badArg(5, "height cannot be negative");
                var colorValue = ((Number) third).intValue();
                if (colorValue < 0) return;
                int colorIndex;
                if (mode == 2) {
                    colorIndex = colorValue;
                    if (colorIndex > 255) throw ccgraphics$badArg(3, "color index out of bounds");
                } else {
                    colorIndex = ccgraphics$colorArgToIndex(3, colorValue);
                }
                gfx.ccgraphics$fillPixels(x, y, w, h, (byte) colorIndex);
            } else if (third instanceof Map<?, ?> table) {
                var hasWidth = args.count() > 3 && args.get(3) instanceof Number;
                if (!hasWidth && args.count() > 3 && args.get(3) != null)
                    throw ccgraphics$badArgType(4, "number", args.get(3));
                var clipWidth = hasWidth ? args.getInt(3) : -1;
                var hasHeight = args.count() > 4 && args.get(4) instanceof Number;
                if (!hasHeight && args.count() > 4 && args.get(4) != null)
                    throw ccgraphics$badArgType(5, "number", args.get(4));
                var rowCount = hasHeight ? args.getInt(4) : table.size();
                if (clipWidth < 0 && hasWidth) throw ccgraphics$badArg(4, "width cannot be negative");
                if (rowCount < 0) throw ccgraphics$badArg(5, "height cannot be negative");
                for (var row = 0; row < rowCount; row++) {
                    var rowData = table.get((double) (row + 1));
                    if (rowData instanceof String str) {
                        var len = clipWidth >= 0 ? Math.min(str.length(), clipWidth) : str.length();
                        var rowBytes = new byte[len];
                        for (var i = 0; i < len; i++) {
                            rowBytes[i] = (byte) str.charAt(i);
                        }
                        gfx.ccgraphics$setPixelBlock(x, y + row, rowBytes, 0, rowBytes.length, rowBytes.length, 1);
                    } else if (rowData instanceof Map<?, ?> innerTable) {
                        var colCount = clipWidth >= 0 ? Math.min(innerTable.size(), clipWidth) : innerTable.size();
                        var rowBytes = new byte[colCount];
                        for (var i = 0; i < colCount; i++) {
                            var existing = gfx.ccgraphics$getPixel(x + i, y + row);
                            rowBytes[i] = existing < 0 ? 0 : (byte) existing;
                        }
                        for (var i = 0; i < colCount; i++) {
                            var val = innerTable.get((double) (i + 1));
                            if (val instanceof Number n) {
                                var cv = n.intValue();
                                if (cv < 0) continue;
                                if (mode == 2) {
                                    rowBytes[i] = (byte) (cv & 0xFF);
                                } else {
                                    rowBytes[i] = (byte) ccgraphics$log2i(cv);
                                }
                            }
                        }
                        gfx.ccgraphics$setPixelBlock(x, y + row, rowBytes, 0, rowBytes.length, rowBytes.length, 1);
                    }
                }
            } else {
                throw ccgraphics$badArgType(3, "table or number", third);
            }
        }
    }

    @LuaFunction
    public final Object getPixels(IArguments args) throws LuaException {
        var x = args.getInt(0);
        var y = args.getInt(1);
        var w = args.getInt(2);
        var h = args.getInt(3);
        if (w < 0) throw ccgraphics$badArg(3, "width cannot be negative");
        if (h < 0) throw ccgraphics$badArg(4, "height cannot be negative");
        var asStrings = args.optBoolean(4, false);
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            var mode = gfx.ccgraphics$getGraphicsMode();
            var result = new HashMap<Integer, Object>();
            for (var row = 0; row < h; row++) {
                if (asStrings) {
                    var sb = new StringBuilder(w);
                    for (var col = 0; col < w; col++) {
                        var pixel = gfx.ccgraphics$getPixel(x + col, y + row);
                        sb.append((char) (pixel < 0 ? 15 : pixel));
                    }
                    result.put(row + 1, sb.toString());
                } else {
                    var rowMap = new HashMap<Integer, Object>();
                    for (var col = 0; col < w; col++) {
                        var pixel = gfx.ccgraphics$getPixel(x + col, y + row);
                        if (pixel < 0) {
                            rowMap.put(col + 1, -1);
                        } else if (mode == 2) {
                            rowMap.put(col + 1, pixel);
                        } else {
                            rowMap.put(col + 1, ccgraphics$indexToColorArg(pixel));
                        }
                    }
                    result.put(row + 1, rowMap);
                }
            }
            return result;
        }
    }

    @LuaFunction
    public final void setFrozen(boolean frozen) throws LuaException {
        var terminal = getTerminal();
        synchronized (terminal) {
            ((IGraphicsTerminal) terminal).ccgraphics$setFrozen(frozen);
        }
    }

    @LuaFunction
    public final boolean getFrozen() throws LuaException {
        return ccgraphics$gfx().ccgraphics$getFrozen();
    }

    @Unique
    private Object[] ccgraphics$getPixelSize() throws LuaException {
        var gfx = ccgraphics$gfx();
        return new Object[]{ gfx.ccgraphics$getGraphicsWidth(), gfx.ccgraphics$getGraphicsHeight() };
    }

    /**
     * Strips @LuaFunction from the original no-arg getSize so the IArguments
     * overload below becomes the sole Lua binding. The no-arg method is kept
     * for any internal Java callers.
     */
    @Overwrite
    public final Object[] getSize() throws LuaException {
        var terminal = getTerminal();
        return new Object[]{ terminal.getWidth(), terminal.getHeight() };
    }

    /**
     * Replacement getSize that accepts an optional mode argument.
     * Works for both the term API and peripherals (peripheral.call).
     */
    @LuaFunction
    public final Object[] getSize(IArguments args) throws LuaException {
        var terminal = getTerminal();
        var value = args.get(0);
        if ((value instanceof Boolean b && b) || (value instanceof Number n && n.intValue() >= 1)) {
            var gfx = (IGraphicsTerminal) terminal;
            return new Object[]{ gfx.ccgraphics$getGraphicsWidth(), gfx.ccgraphics$getGraphicsHeight() };
        } else if (value != null && !(value instanceof Boolean) && !(value instanceof Number)) {
            throw ccgraphics$badArgType(1, "boolean or number", value);
        }
        return new Object[]{ terminal.getWidth(), terminal.getHeight() };
    }

    @Inject(method = "setPaletteColour", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onSetPaletteColour(IArguments args, CallbackInfo ci) throws LuaException {
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            if (gfx.ccgraphics$getGraphicsMode() != 2) return;

            var index = args.getInt(0);
            if (index < 0 || index > 255) throw ccgraphics$badArg(1, "invalid color " + index);

            double r, g, b;
            if (args.count() < 3 || args.get(2) == null) {
                var hex = args.getInt(1);
                var rgb = Palette.decodeRGB8(hex);
                r = rgb[0]; g = rgb[1]; b = rgb[2];
            } else {
                r = args.getFiniteDouble(1);
                g = args.getFiniteDouble(2);
                b = args.getFiniteDouble(3);
            }

            if (index < 16) {
                terminal.getPalette().setColour(15 - index, r, g, b);
                terminal.setChanged();
            } else {
                gfx.ccgraphics$setExtPaletteColor(index, r, g, b);
            }
            ci.cancel();
        }
    }

    @Inject(method = "getPaletteColour", at = @At("HEAD"), cancellable = true)
    private void ccgraphics$onGetPaletteColour(int colourArg, CallbackInfoReturnable<Object[]> cir) throws LuaException {
        var terminal = getTerminal();
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            if (gfx.ccgraphics$getGraphicsMode() != 2) return;

            if (colourArg < 0 || colourArg > 255) throw ccgraphics$badArg(1, "invalid color " + colourArg);

            double[] rgb;
            if (colourArg < 16) {
                rgb = terminal.getPalette().getColour(15 - colourArg);
            } else {
                rgb = gfx.ccgraphics$getExtPaletteColor(colourArg);
            }
            cir.setReturnValue(new Object[]{ rgb[0], rgb[1], rgb[2] });
        }
    }
}
