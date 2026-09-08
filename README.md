# CosyWinterVibes

Paper plugin that runs a temporary winter event inside a configurable base zone: snowy biome, natural snowfall, no ice, and a full reversible cleanup.

## Requirements

- Paper 1.21.4+ server (API version declared in `pom.xml`)
- Java 21+ to build

## Features

- **Zone-based winter** — paint a snowy biome in a circular radius around a set center without permanently altering the world outside that zone.
- **Reversible biomes** — original biomes are snapshotted to disk before any change and restored on `/winter stop` (and after a crash/restart).
- **Vanilla snowfall** — once the biome is snowy, Minecraft weather places snow layers naturally; the plugin only tracks them for cleanup.
- **No frozen water by default** — ice and frosted ice are cancelled inside the zone so farms and redstone stay intact. Optionally, surface water (rivers/lakes) can be allowed to freeze while covered/farm water stays safe (see `freeze-surface-water` below).
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
| `/winter freeze` | Shows whether surface-water freezing is enabled |
| `/winter freeze on\|off` | Enables/disables ice on open surface water (saves to `config.yml`; takes effect for new ice immediately) |
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
| `freeze-surface-water` | boolean | **Default `false`.** When `false`, all ICE/FROSTED_ICE is cancelled in the zone (safest for farms). When `true`, vanilla ICE is allowed only on truly exposed surface water (block above is AIR **and** `getLightFromSky() > 0`); FROSTED_ICE (Frost Walker) is still always cancelled. |

## Behavior

- **Start**: snapshots original biomes, paints the target snowy biome in batches, optionally forces weather.
- **During winter**: vanilla forms snow; snow layers are capped and tracked. FROSTED_ICE is always cancelled. ICE is cancelled by default, or — if `freeze-surface-water: true` — allowed only on open surface water and tracked for cleanup.
- **Stop**: restores original biomes, removes tracked snow, melts tracked ice back to water, fixes white (`snowy`) grass, clears forced weather if the plugin enabled it. Ice/snow that was already there before the event (natural frozen rivers, snowy mountains, etc.) is detected up front and never touched.
- **Cleanup**: same snow/ice/grass sweep using the configured zone, useful after an interrupted event or older leftover snow/ice.

### Freezing rivers/lakes without breaking farms

Enable with `/winter freeze on` (or set `freeze-surface-water: true` in config). Water that sits under any block — including a simple bottom slab — never freezes, because it no longer has direct sky exposure. So to keep a farm's water channel ice-free while still letting nearby rivers/lakes freeze over, just cover the farm's water with a bottom half-slab (e.g. `minecraft:stone_slab` in the bottom position). The water underneath stays liquid and fully functional, while open water elsewhere in the zone can freeze naturally.

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
