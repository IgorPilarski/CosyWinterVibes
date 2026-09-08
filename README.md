# CosyWinterVibes

Paper plugin that runs a temporary winter event inside a configurable base zone: snowy biome, natural snowfall, no ice, and a full reversible cleanup.

## Requirements

- Paper 1.21.4+ server (API version declared in `pom.xml`)
- Java 21+ to build

## Features

- **Zone-based winter** — paint a snowy biome in a circular radius around a set center without permanently altering the world outside that zone.
- **Reversible biomes** — original biomes are snapshotted to disk before any change and restored on `/winter stop` (and after a crash/restart).
- **Vanilla snowfall** — once the biome is snowy, Minecraft weather places snow layers naturally; the plugin only tracks them for cleanup.
- **No frozen water** — ice and frosted ice are cancelled inside the zone so farms and redstone stay intact.
- **Snow layer cap** — limits how tall natural snow layers can grow.
- **Full cleanup** — removes tracked snow, sweeps the zone by chunk, and clears leftover `snowy` grass.
- **Crash-safe state** — `winter-state.yml` persists progress so painting/cleanup can resume after a restart.

## Build

No global Maven install required — the repo includes a Maven Wrapper.

```powershell
.\build.ps1 package
```

Output: `target/CosyWinterVibes.jar` — copy it into your server's `plugins/` folder.

## Commands

| Command | Description |
|---------|-------------|
| `/winter setcenter <radius>` | Sets the zone center to your position and radius |
| `/winter setcenter <radius> <x> <z>` | Sets the zone center to explicit coordinates (world = your world, or configured/default from console) |
| `/winter start` | Starts the winter event |
| `/winter stop` | Stops winter, restores biomes, cleans snow |
| `/winter status` | Shows whether winter is active |
| `/winter cleanup` | Cleans leftover snow / white grass without starting winter |
| `/winter help` | Shows command help |

Permission: `cosywintervibes.admin` (default: OP)

## Configuration (`plugins/CosyWinterVibes/config.yml`)

| Key | Type | Description |
|---|---|---|
| `zone.world` | string | World name. Empty until set with `/winter setcenter`. |
| `zone.center-x` / `zone.center-z` | int | Zone center coordinates. |
| `zone.radius` | int | Horizontal radius in blocks. |
| `zone.min-y` / `zone.max-y` | int | Biome paint height range. `-1` = world min/max. |
| `zone.target-biome` | string | Namespaced biome key (default `minecraft:snowy_taiga`). |
| `force-weather-on-start` | boolean | Force storm weather on start (world-wide weather, local biome). |
| `painting.columns-per-batch` | int | How many biome columns to paint per batch. |
| `painting.ticks-between-batches` | int | Ticks between paint/restore batches. |
| `melting.blocks-per-batch` | int | Tracked snow blocks removed per tick batch. |
| `melting.ticks-between-batches` | int | Ticks between cleanup batches. |
| `melting.chunks-per-tick` | int | Chunks swept per cleanup tick. |
| `melting.scan-depth` | int | Blocks downward from surface to scan for snow. |
| `max-snow-layers` | int | Max natural snow layer level (1–8). |

## Behavior

- **Start**: snapshots original biomes, paints the target snowy biome in batches, optionally forces weather.
- **During winter**: vanilla forms snow; ice is cancelled; snow layers are capped and tracked.
- **Stop**: restores original biomes, removes snow, fixes white (`snowy`) grass, clears forced weather if the plugin enabled it.
- **Cleanup**: same snow/grass sweep using the configured zone, useful after an interrupted event or older leftover snow.

Biome painting uses Minecraft's 4×4 biome cells. Cleanup pads the radius so overhanging cells are included.

## Project structure

```
src/main/java/com/cosywintervibes/
  CosyWinterVibes.java              — main class, lifecycle
  winter/
    WinterZoneManager.java          — biome paint/restore, melt, persistence
    WinterWeatherListener.java      — snow tracking, ice cancel, chunk load
    WinterCommand.java              — /winter admin commands
```

## License

MIT — see [LICENSE](LICENSE).
