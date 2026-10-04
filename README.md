# MCMetal

A native **Metal** rendering backend for Minecraft on Apple Silicon Macs, as a Fabric mod. It replaces the game's renderer with one written directly against Metal, and can run OptiFine/Iris-format **shaderpacks** on it.

## Features

- **Native Metal renderer** built for Apple's tile-based GPUs. It merges render passes, folds clears into load actions and hoists uploads out of passes.
- **Shaderpacks**: runs OptiFine/Iris packs by translating GLSL to Metal through SPIR-V. Tested with BSL and Complementary Reimagined.
- **Render Scale with MetalFX**: shaderpacks can render at reduced resolution and be upscaled with Apple's MetalFX. The HUD and text stay at full resolution. The default is 75%.
- **Low-latency presentation**: with vsync off, every finished frame reaches the screen.

## Requirements

- Mac with Apple Silicon (M1 or newer), macOS 15+
- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/use/) 0.19.5+
- Java 25

## Install

1. Install Fabric Loader for Minecraft 26.3.
2. Download `mcmetal-0.1.1.jar` from [Releases](../../releases) and put it in your `mods` folder.
3. Launch the game. Metal is used automatically on supported Macs.

### Shaderpacks

Put pack `.zip` files in `.minecraft/shaderpacks/`, then go to **Options → Video Settings → Shader Packs...**. That screen lets you pick a pack, open its options and change the **Render Scale**.

## Configuration

`config/mcmetal.properties` (each key can also be set with `-Dmcmetal.<key>=...`):

| Key | Default | Description |
|---|---|---|
| `renderScale` | `1.0` | Whole-game render resolution (0.25-1.0) |
| `frameLimiter` | `false` | Low-latency limiter: one frame per display refresh |

Use `-Dmcmetal.disable=true` to fall back to the vanilla backend.

## Building

Requires Xcode command line tools (for the Objective-C++ native library) and JDK 25.

```sh
./gradlew build          # jar in build/libs/
./gradlew runClient      # dev client
```

## Status

This is early software (0.1.x). Expect rough edges, especially with shaderpacks other than the ones listed above. Bug reports are welcome.

## License

[MIT](LICENSE). Not affiliated with Mojang or Microsoft.
