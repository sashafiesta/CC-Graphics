# CC: Graphics

CraftOS-PC compatible [graphics mode](https://www.craftos-pc.cc/docs/gfxmode) for CC:Tweaked terminals.

**Minecraft 1.21.1** | **CC:Tweaked 1.117.0+** | **Fabric & NeoForge**

## What's done

- Graphics modes 0, 1, 2 with matching Lua API
- Bundled CraftOS-PC programs: gfxpaint, pngview, raycast
- Network sync and NBT persistence
- Compressed graphics packets (LZ4 + delta)
- Monitor Support

## Configuration

- `allow_grayscale_graphics` - allow graphics mode on non-color (standard) computers (default: `false`)
- `compression` - network compression algorithm for graphics data (default: `LZ4_DIFF`)
- `ccgraphics:graphics_disabled` data component - disable graphics on individual computers

## Known limitations

- Pocket computers won't display graphics on the in-hand item render (only in the GUI)
- Graphics on pocket computers might be unstable
- `term.screenshot()`, `term.showMouse()`, and `term.relativeMouse()` are not implemented (SDL-only in CraftOS-PC)

## License

MIT
