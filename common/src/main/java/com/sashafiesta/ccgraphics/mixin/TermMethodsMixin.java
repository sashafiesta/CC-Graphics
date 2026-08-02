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

    /**
     * Rejects pixel regions bigger than the screen itself before anything is allocated.
     * <p>
     * {@code IArguments.getInt} is {@code (int) getLong(index)} with only a NaN/Inf check, so
     * Lua can ask for a region of up to {@link Integer#MAX_VALUE} in each direction. Building
     * the reply is O(w*h) allocation on the computer thread, and the terminal monitor it needs
     * is the same one {@code NetworkedTerminal.write} takes from the server tick thread via
     * {@code TerminalState.create} - so an oversized request parks the main thread somewhere
     * CC:T's runaway protection cannot reach (hardAbort only sets a flag for the Cobalt debug
     * hook, and Thread.interrupt neither breaks a plain Java loop nor releases a held monitor).
     * A region larger than the whole screen can never return anything the caller could not get
     * from a screen-sized read, so bounding it costs no real functionality.
     */
    @Unique
    private static void ccgraphics$checkPixelRegion(int widthArg, int heightArg, int w, int h, IGraphicsTerminal gfx) throws LuaException {
        var max = (long) gfx.ccgraphics$getGraphicsWidth() * gfx.ccgraphics$getGraphicsHeight();
        if (w > max) throw ccgraphics$badArg(widthArg, "width " + w + " is larger than the terminal (" + max + " pixels)");
        if (h > max) throw ccgraphics$badArg(heightArg, "height " + h + " is larger than the terminal (" + max + " pixels)");
        var area = (long) w * h;
        if (area > max) throw ccgraphics$badArg(widthArg, "region of " + area + " pixels is larger than the terminal (" + max + " pixels)");
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
            throw new LuaException("Graphics mode is not available on this display device");
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            if (mode != 0 && gfx.ccgraphics$isGraphicsDisabled()) throw new LuaException("Graphics mode is disabled on this display device");
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
        // Argument access happens before the monitor is taken - see ccgraphics$checkPixelRegion
        // for why nothing caller-controlled should run while the terminal is locked.
        var rawColor = args.getInt(2);
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            var mode = gfx.ccgraphics$getGraphicsMode();
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
        var gfx = (IGraphicsTerminal) terminal;

        // Every argument is read before the monitor is taken. IArguments.get deep-converts a
        // Lua table into a Map, which is unbounded work driven entirely by the caller; doing
        // it under the lock would stall the server tick thread. See ccgraphics$checkPixelRegion.
        // The terminal's own mode and dimensions are read inside the lock instead, so the mode
        // that selects the colour conversion is the mode in force when the pixels land.
        var third = args.get(2);
        if (third instanceof Number) {
            var w = args.getInt(3);
            var h = args.getInt(4);
            if (w < 0) throw ccgraphics$badArg(4, "width cannot be negative");
            if (h < 0) throw ccgraphics$badArg(5, "height cannot be negative");
            var colorValue = ((Number) third).intValue();
            if (colorValue < 0) return;
            // fillPixels clips to the buffer and allocates nothing, so an oversized w/h here
            // is already O(screen) - no extra bound needed.
            synchronized (terminal) {
                if (x >= gfx.ccgraphics$getGraphicsWidth() || y >= gfx.ccgraphics$getGraphicsHeight()) return;
                var mode = gfx.ccgraphics$getGraphicsMode();
                int colorIndex;
                if (mode == 2) {
                    colorIndex = colorValue;
                    if (colorIndex > 255) throw ccgraphics$badArg(3, "color index out of bounds");
                } else {
                    colorIndex = ccgraphics$colorArgToIndex(3, colorValue);
                }
                gfx.ccgraphics$fillPixels(x, y, w, h, (byte) colorIndex);
            }
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

            synchronized (terminal) {
                var gw = gfx.ccgraphics$getGraphicsWidth();
                var gh = gfx.ccgraphics$getGraphicsHeight();
                if (x >= gw || y >= gh) return;
                var mode = gfx.ccgraphics$getGraphicsMode();

                // Clip the row and column ranges to the buffer up front. setPixelBlock already
                // discards off-screen rows and columns, so this is invisible to Lua, but it turns
                // a caller-supplied rowCount/clipWidth of Integer.MAX_VALUE from minutes of work
                // (and gigabytes of per-row byte[]) under the monitor into O(screen). Long maths
                // throughout because x/y may be Integer.MIN_VALUE.
                var firstRow = Math.max(0L, -(long) y);
                var lastRow = Math.min(rowCount, (long) gh - y);
                if (lastRow <= firstRow) return;
                var rowFrom = (int) firstRow;
                var rowTo = (int) lastRow;
                var colOffset = Math.max(0L, -(long) x);

                for (var row = rowFrom; row < rowTo; row++) {
                    var screenY = y + row;
                    var rowData = table.get((double) (row + 1));
                    if (rowData instanceof String str) {
                        var len = clipWidth >= 0 ? Math.min(str.length(), clipWidth) : str.length();
                        var colEnd = Math.min(len, (long) gw - x);
                        if (colEnd <= colOffset) continue;
                        var from = (int) colOffset;
                        var count = (int) colEnd - from;
                        var rowBytes = new byte[count];
                        for (var i = 0; i < count; i++) {
                            rowBytes[i] = (byte) str.charAt(from + i);
                        }
                        gfx.ccgraphics$setPixelBlock(x + from, screenY, rowBytes, 0, count, count, 1);
                    } else if (rowData instanceof Map<?, ?> innerTable) {
                        var colCount = clipWidth >= 0 ? Math.min(innerTable.size(), clipWidth) : innerTable.size();
                        var colEnd = Math.min(colCount, (long) gw - x);
                        if (colEnd <= colOffset) continue;
                        var from = (int) colOffset;
                        var count = (int) colEnd - from;
                        var rowBytes = new byte[count];
                        for (var i = 0; i < count; i++) {
                            var existing = gfx.ccgraphics$getPixel(x + from + i, screenY);
                            rowBytes[i] = existing < 0 ? 0 : (byte) existing;
                        }
                        for (var i = 0; i < count; i++) {
                            var val = innerTable.get((double) (from + i + 1));
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
                        gfx.ccgraphics$setPixelBlock(x + from, screenY, rowBytes, 0, count, count, 1);
                    }
                }
            }
        } else {
            throw ccgraphics$badArgType(3, "table or number", third);
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
        ccgraphics$checkPixelRegion(3, 4, w, h, (IGraphicsTerminal) terminal);

        // Copy the on-screen part of the region out under the monitor, then release it and
        // build the Lua tables. The table build is O(w*h) allocation and must not run while
        // the terminal is locked - NetworkedTerminal.write takes the same monitor from the
        // server tick thread. Coordinates are clipped with long maths because x/y may be
        // Integer.MIN_VALUE.
        int mode;
        int colMin, colMax, rowMin, rowMax, regionWidth;
        byte[] region;
        synchronized (terminal) {
            var gfx = (IGraphicsTerminal) terminal;
            mode = gfx.ccgraphics$getGraphicsMode();
            var gw = gfx.ccgraphics$getGraphicsWidth();
            var gh = gfx.ccgraphics$getGraphicsHeight();
            colMin = (int) Math.min(w, Math.max(0L, -(long) x));
            colMax = (int) Math.max(colMin, Math.min(w, (long) gw - x));
            rowMin = (int) Math.min(h, Math.max(0L, -(long) y));
            rowMax = (int) Math.max(rowMin, Math.min(h, (long) gh - y));
            regionWidth = colMax - colMin;
            var regionHeight = rowMax - rowMin;
            region = new byte[regionWidth * regionHeight];
            if (regionWidth > 0) {
                var buf = gfx.ccgraphics$getGraphics();
                for (var row = 0; row < regionHeight; row++) {
                    System.arraycopy(buf, (y + rowMin + row) * gw + x + colMin, region, row * regionWidth, regionWidth);
                }
            }
        }

        // Pixels outside the buffer read back as -1 (or filler 15 in string form), matching
        // CraftOS-PC, so partially- and fully-off-screen requests behave exactly as before.
        var result = new HashMap<Integer, Object>();
        for (var row = 0; row < h; row++) {
            var inRow = row >= rowMin && row < rowMax;
            var rowOffset = inRow ? (row - rowMin) * regionWidth : 0;
            if (asStrings) {
                var chars = new char[w];
                for (var col = 0; col < w; col++) {
                    chars[col] = inRow && col >= colMin && col < colMax
                        ? (char) (region[rowOffset + col - colMin] & 0xFF)
                        : (char) 15;
                }
                result.put(row + 1, new String(chars));
            } else {
                var rowMap = new HashMap<Integer, Object>();
                for (var col = 0; col < w; col++) {
                    if (!inRow || col < colMin || col >= colMax) {
                        rowMap.put(col + 1, -1);
                    } else {
                        var pixel = region[rowOffset + col - colMin] & 0xFF;
                        rowMap.put(col + 1, mode == 2 ? pixel : ccgraphics$indexToColorArg(pixel));
                    }
                }
                result.put(row + 1, rowMap);
            }
        }
        return result;
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
        var gfx = (IGraphicsTerminal) terminal;
        // Cheap pre-filter so the ordinary 16-colour case never parses arguments here at all.
        // The check that matters is the one under the monitor below.
        if (gfx.ccgraphics$getGraphicsMode() != 2) return;

        // Parsed before the monitor is taken: args.get deep-converts a Lua table, so
        // term.setPaletteColour(0, 0, hugeTable) would otherwise hold the terminal for as
        // long as the caller likes. See ccgraphics$checkPixelRegion.
        var index = args.getInt(0);

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

        synchronized (terminal) {
            // Re-checked under the monitor: the Lua thread may have left mode 2 since the
            // pre-filter, and both the 0..255 range and the 15-index flip below are mode-2
            // semantics. Bailing here leaves the call to vanilla, which is correct for
            // whatever mode is now in force.
            if (gfx.ccgraphics$getGraphicsMode() != 2) return;
            if (index < 0 || index > 255) throw ccgraphics$badArg(1, "invalid color " + index);

            if (index < 16) {
                terminal.getPalette().setColour(15 - index, r, g, b);
                terminal.setChanged();
            } else {
                gfx.ccgraphics$setExtPaletteColor(index, r, g, b);
            }
            // Cancelled only on the path that actually applied the colour.
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
