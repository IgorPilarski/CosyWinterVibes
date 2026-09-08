package com.cosywintervibes.winter;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Snowable;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Core of the plugin. Responsible for:
 *  - painting a real biome (World#setBiome) within a radius around the center,
 *    SPREAD over time (batch/tick) to avoid a lag spike,
 *  - saving original biomes to disk BEFORE any change, so a restart/crash
 *    during the event loses nothing,
 *  - tracking snow (and, optionally, surface ice) blocks that vanilla
 *    weather formed inside the zone (see WinterWeatherListener), so
 *    "/winter stop" cleans up ONLY what we caused,
 *  - remembering snow/ice that was ALREADY there before the event (natural
 *    mountains, a frozen river, an existing snowy biome inside the zone)
 *    so cleanup never touches it,
 *  - melting snow/ice and restoring the original biome.
 *
 * We deliberately only touch the world through public Bukkit/Paper API
 * (setBiome / setBlockData) — normal main-thread writes, same category as
 * placing a block.
 */
public final class WinterZoneManager {

    /**
     * Minecraft stores biomes in 4x4x4 cells. We paint columns every 4 blocks,
     * so a winter cell always slightly overhangs the zone circle.
     * Padding of 8 covers that overflow with margin.
     * Public so {@link WinterWeatherListener} uses the exact same value —
     * keeping two separate copies in sync by hand is a bug waiting to happen.
     */
    public static final int ZONE_PADDING = 8;

    /** Terrain column (biome is 3D; we set it for the full column height at once). */
    record ColumnPos(int x, int z) {}

    /** A snow block we observed being formed. */
    record SnowPos(int x, int y, int z) {}

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final File stateFile;

    private boolean active = false;
    /** true from stop/cleanup until the exact zone scan finishes. */
    private boolean cleaningUp = false;

    // Frozen parameters of the current (or last) event.
    // Loaded from config.yml ONLY at /winter start — editing config.yml mid-event
    // must not break restore math.
    private World world;
    private int centerX;
    private int centerZ;
    private int radius;
    private int minY;
    private int maxY;
    private Biome targetBiome;
    private boolean weatherForcedByUs = false;

    private final Map<ColumnPos, Biome> originalBiomes = new HashMap<>();
    private final Set<SnowPos> trackedSnow = new HashSet<>();
    /**
     * Snow layers that were already sitting in the zone BEFORE "/winter start"
     * (natural terrain — mountains, an existing snowy biome, etc.). Detected
     * once at start and never touched again: cleanup only removes snow that
     * is NOT in this set, so pre-existing natural snow always survives the
     * whole start -> stop -> cleanup cycle untouched.
     */
    private final Set<SnowPos> preexistingSnow = new HashSet<>();

    /**
     * ICE blocks that vanilla formed on exposed surface water while
     * {@code freeze-surface-water} was enabled (see {@link WinterWeatherListener}).
     * Tracked/cleaned up exactly like {@link #trackedSnow}.
     */
    private final Set<SnowPos> trackedIce = new HashSet<>();
    /**
     * ICE that was already sitting in the zone BEFORE "/winter start" (a
     * naturally frozen river/lake, or ice placed by someone else). Detected
     * once at start (and lazily on chunk load) so cleanup never melts it —
     * mirrors {@link #preexistingSnow}.
     */
    private final Set<SnowPos> preexistingIce = new HashSet<>();

    private BukkitTask paintTask;
    private BukkitTask restoreTask;
    private BukkitTask meltTask;

    public WinterZoneManager(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "winter-state.yml");
    }

    public org.bukkit.plugin.java.JavaPlugin getPlugin() {
        return plugin;
    }

    // =================================================================
    //  Public API used by the command and listener
    // =================================================================

    public boolean isActive() {
        return active;
    }

    public boolean isCleaningUp() {
        return cleaningUp;
    }

    public int getMaxSnowLayers() {
        return Math.max(1, Math.min(8, plugin.getConfig().getInt("max-snow-layers", 3)));
    }

    /**
     * Whether vanilla ICE is allowed to form on exposed surface water inside
     * the zone (see config comment for {@code freeze-surface-water}).
     * FROSTED_ICE (Frost Walker) is always cancelled regardless of this flag.
     * Read live from config so {@link #setFreezeSurfaceWater} takes effect
     * immediately for newly forming ice (even mid-event).
     */
    public boolean isFreezeSurfaceWaterEnabled() {
        return plugin.getConfig().getBoolean("freeze-surface-water", false);
    }

    /**
     * Persist {@code freeze-surface-water} to config.yml. Takes effect for
     * newly forming ice right away; already-formed tracked ice still melts
     * on stop/cleanup as usual.
     */
    public void setFreezeSurfaceWater(CommandSender sender, boolean enabled) {
        plugin.getConfig().set("freeze-surface-water", enabled);
        plugin.saveConfig();
        if (enabled) {
            sender.sendMessage("§aSurface water freezing enabled — open rivers/lakes may freeze;"
                    + " water under slabs/blocks stays liquid. Cover farm water with a bottom slab.");
        } else {
            sender.sendMessage("§aSurface water freezing disabled — new ICE in the zone is cancelled."
                    + " Already-formed ice will melt on /winter stop or /winter cleanup.");
        }
    }

    /** Whether the location is inside the frozen zone (horizontal distance from center). */
    public boolean isInsideZone(Location loc) {
        return isInsideZone(loc, 0);
    }

    /**
     * Padded variant — used for snow tracking, because 4x4 biomes can bleed
     * slightly outside the zone circle. Also works during cleaningUp (does not require active).
     */
    public boolean isInsideZone(Location loc, int padding) {
        if ((!active && !cleaningUp) || world == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().equals(world)) return false;
        return isInsideRadius(loc.getBlockX(), loc.getBlockZ(), padding);
    }

    private boolean isInsideRadius(int x, int z, int padding) {
        long dx = x - centerX;
        long dz = z - centerZ;
        long r = (long) radius + padding;
        return dx * dx + dz * dz <= r * r;
    }

    /** Called by the listener when vanilla forms a snow layer in the zone. */
    public void trackSnow(int x, int y, int z) {
        trackedSnow.add(new SnowPos(x, y, z));
    }

    /** Called by the listener when vanilla forms allowed surface ICE in the zone. */
    public void trackIce(int x, int y, int z) {
        trackedIce.add(new SnowPos(x, y, z));
    }

    public void cancelRunningTasks() {
        cancel(paintTask);
        cancel(restoreTask);
        cancel(meltTask);
        paintTask = restoreTask = meltTask = null;
        releaseChunkTickets();
        saveState();
    }

    private void cancel(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private void releaseChunkTickets() {
        if (world != null) {
            world.removePluginChunkTickets(plugin);
        }
    }

    // =================================================================
    //  START
    // =================================================================

    public void start(CommandSender sender) {
        if (active) {
            sender.sendMessage("§eWinter is already running.");
            return;
        }
        if (cleaningUp) {
            sender.sendMessage("§cWait until cleanup from the previous winter finishes.");
            return;
        }

        var cfg = plugin.getConfig();
        String worldName = cfg.getString("zone.world", "");
        if (worldName == null || worldName.isBlank()) {
            sender.sendMessage("§cZone center is not set. Use first: /winter setcenter <radius>");
            return;
        }
        World resolvedWorld = Bukkit.getWorld(worldName);
        if (resolvedWorld == null) {
            sender.sendMessage("§cWorld '" + worldName + "' from config does not exist / is not loaded.");
            return;
        }

        String biomeKey = cfg.getString("zone.target-biome", "minecraft:snowy_taiga");
        Biome resolvedBiome = resolveBiome(biomeKey);
        if (resolvedBiome == null) {
            sender.sendMessage("§cUnknown biome in config: " + biomeKey);
            return;
        }

        applyZoneBoundsFromConfig(resolvedWorld);
        this.targetBiome = resolvedBiome;

        this.originalBiomes.clear();
        this.trackedSnow.clear();
        this.preexistingSnow.clear();
        this.trackedIce.clear();
        this.preexistingIce.clear();
        this.active = true;

        Deque<ColumnPos> paintQueue = new ArrayDeque<>();
        collectColumnsInZone(paintQueue);

        // Snapshot ORIGINAL biomes BEFORE any change and save to disk before
        // painting starts — safety net if the server crashes mid-operation.
        // NOTE: we only sample ONE Y (sea level) per column, not per 4-block
        // cell like painting does. This is intentional: the whole point of
        // this plugin is a cosmetic SURFACE effect (snow), so restoring the
        // surface biome is what matters. A side effect is that if a column
        // has a different biome underground (e.g. a lush cave / dripstone
        // cave under the base), "/winter stop" will overwrite that
        // underground biome with the surface one too, instead of restoring
        // it exactly. Fixing that would require snapshotting every 4-block Y
        // cell instead of one sample per column (much bigger state file) —
        // not done here since it doesn't affect the actual snow feature.
        int sampleY = clamp(world.getSeaLevel(), minY, maxY);
        for (ColumnPos c : paintQueue) {
            originalBiomes.put(c, world.getBiome(c.x(), sampleY, c.z()));
        }

        // Snapshot natural snow/ice BEFORE forcing weather. If we detect during
        // painting (after setStorm), vanilla may already have placed sparse
        // snow/ice on columns not yet painted — those would be wrongly marked
        // "preexisting" and survive /winter stop as the dotted leftovers.
        snapshotPreexistingInPaintQueue(paintQueue);
        saveState();

        if (cfg.getBoolean("force-weather-on-start", true) && !world.hasStorm()) {
            world.setStorm(true);
            world.setThundering(false);
            weatherForcedByUs = true;
        }

        int columnsPerBatch = getPaintColumnsPerBatch();
        long ticksBetween = getPaintTicksBetweenBatches();

        sender.sendMessage("§bWinter is starting — painting " + paintQueue.size() + " terrain columns in the background...");

        paintTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            processPaintBatch(paintQueue, columnsPerBatch);
            if (paintQueue.isEmpty()) {
                cancel(paintTask);
                paintTask = null;
                saveState();
                Bukkit.broadcastMessage("§b❄ Winter at the base is fully active. Wait for precipitation — snow will start covering the ground.");
            }
        }, 0L, ticksBetween);
    }

    private void processPaintBatch(Deque<ColumnPos> queue, int batchSize) {
        Set<Long> touchedChunks = new HashSet<>();
        for (int i = 0; i < batchSize && !queue.isEmpty(); i++) {
            ColumnPos c = queue.poll();
            paintColumn(c, targetBiome);
            touchedChunks.add(chunkKey(c.x() >> 4, c.z() >> 4));
        }
        refreshChunks(touchedChunks);
    }

    /**
     * Full-column preexisting snapshot for every chunk touched by the paint
     * queue (not only every-4 biome sample columns). Must run before weather
     * is forced so event snow/ice is never mistaken for natural terrain.
     */
    private void snapshotPreexistingInPaintQueue(Deque<ColumnPos> paintQueue) {
        int scanDepth = getMeltScanDepth();
        Set<Long> chunks = new HashSet<>();
        for (ColumnPos c : paintQueue) {
            chunks.add(chunkKey(c.x() >> 4, c.z() >> 4));
        }
        for (long key : chunks) {
            snapshotPreexistingInChunk((int) (key >> 32), (int) key, scanDepth);
        }
    }

    private void snapshotPreexistingInChunk(int cx, int cz, int scanDepth) {
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                if (!isInsideRadius(x, z, ZONE_PADDING)) continue;
                detectPreexisting(x, z, scanDepth);
            }
        }
    }

    /**
     * Detect-only pass (never modifies blocks): records every snow layer
     * found in the column into {@link #preexistingSnow}, and every ICE block
     * into {@link #preexistingIce}, using the same surface-downward scan
     * that cleanup will later use. Call only for terrain that predates the
     * event (before forcing weather, or on first load of a previously
     * unloaded chunk that could not have received event weather ticks).
     */
    private void detectPreexisting(int x, int z, int scanDepth) {
        int top = Math.min(maxY, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE));
        int bottom = Math.max(minY, top - scanDepth);
        for (int y = top; y >= bottom; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.SNOW) {
                preexistingSnow.add(new SnowPos(x, y, z));
            } else if (type == Material.ICE) {
                preexistingIce.add(new SnowPos(x, y, z));
            }
        }
    }

    private void paintColumn(ColumnPos c, Biome biome) {
        for (int y = minY; y <= maxY; y += 4) {
            world.setBiome(c.x(), y, c.z(), biome);
        }
    }

    // Shared config readers for painting/restoring — used by start(), stop(),
    // loadPersistedState() and resumeAfterRestart() so the defaults live in
    // exactly one place.
    private int getPaintColumnsPerBatch() {
        return Math.max(1, plugin.getConfig().getInt("painting.columns-per-batch", 40));
    }

    private long getPaintTicksBetweenBatches() {
        return Math.max(1, plugin.getConfig().getInt("painting.ticks-between-batches", 2));
    }

    private int getMeltScanDepth() {
        return Math.max(32, plugin.getConfig().getInt("melting.scan-depth", 128));
    }

    // =================================================================
    //  STOP
    // =================================================================

    public void stop(CommandSender sender) {
        if (!active) {
            sender.sendMessage("§eWinter is not active.");
            return;
        }
        active = false;
        cleaningUp = true;
        cancel(paintTask);
        paintTask = null;

        if (weatherForcedByUs) {
            world.setStorm(false);
            weatherForcedByUs = false;
        }

        var cfg = plugin.getConfig();
        saveState();

        Deque<ColumnPos> restoreQueue = new ArrayDeque<>(originalBiomes.keySet());
        int columnsPerBatch = getPaintColumnsPerBatch();
        long ticksBetween = getPaintTicksBetweenBatches();

        restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Set<Long> touchedChunks = new HashSet<>();
            for (int i = 0; i < columnsPerBatch && !restoreQueue.isEmpty(); i++) {
                ColumnPos c = restoreQueue.poll();
                Biome original = originalBiomes.remove(c);
                if (original != null) {
                    paintColumn(c, original);
                    touchedChunks.add(chunkKey(c.x() >> 4, c.z() >> 4));
                }
            }
            refreshChunks(touchedChunks);
            if (restoreQueue.isEmpty()) {
                cancel(restoreTask);
                restoreTask = null;
                saveState();
            }
        }, 0L, ticksBetween);

        startMeltAndSweep(cfg);

        sender.sendMessage("§bWinter is ending — biome returns to normal, snow and white grass will be cleaned up in the background.");
    }

    /**
     * Fixes leftovers after an already finished winter (or older-version bugs):
     * removes snow layers in the zone and clears the snowy flag on grass.
     * Does not require an active event — reads center/radius from config.yml.
     */
    public void cleanup(CommandSender sender) {
        if (active) {
            sender.sendMessage("§cWinter is active. Use /winter stop — it cleans up by itself.");
            return;
        }
        if (cleaningUp) {
            sender.sendMessage("§eA cleanup is already running. Wait for it to finish.");
            return;
        }
        if (!resolveZoneFromConfig(sender)) {
            return;
        }
        cancel(meltTask);
        meltTask = null;
        trackedSnow.clear();
        trackedIce.clear();
        cleaningUp = true;
        saveState();
        startMeltAndSweep(plugin.getConfig());
        sender.sendMessage("§bCleaning remaining snow and white grass in the zone...");
    }

    /**
     * Fast cleanup:
     *  1) instantly remove tracked snow layers and melt tracked ice
     *     (no layer-by-layer melt),
     *  2) scan the zone by CHUNKS (memory locality + fewer tickets at once),
     *  3) in each column only look downward from the surface (not full world height).
     */
    private void startMeltAndSweep(org.bukkit.configuration.file.FileConfiguration cfg) {
        int blocksPerBatch = Math.max(50, cfg.getInt("melting.blocks-per-batch", 800));
        long ticksBetween = Math.max(1, cfg.getInt("melting.ticks-between-batches", 1));
        int chunksPerTick = Math.max(1, cfg.getInt("melting.chunks-per-tick", 3));
        int scanDepth = getMeltScanDepth();

        Deque<SnowPos> trackedQueue = new ArrayDeque<>(trackedSnow);
        Deque<SnowPos> trackedIceQueue = new ArrayDeque<>(trackedIce);
        Deque<Long> chunkQueue = new ArrayDeque<>();
        final boolean[] chunksPrepared = {false};

        meltTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Phase 1: instantly remove what we tracked ourselves.
            if (!trackedQueue.isEmpty()) {
                for (int i = 0; i < blocksPerBatch && !trackedQueue.isEmpty(); i++) {
                    removeSnowFully(trackedQueue.poll());
                }
                if (trackedQueue.isEmpty()) {
                    trackedSnow.clear();
                }
                return;
            }

            // Phase 2: instantly melt tracked ice back to water.
            if (!trackedIceQueue.isEmpty()) {
                for (int i = 0; i < blocksPerBatch && !trackedIceQueue.isEmpty(); i++) {
                    meltIceFully(trackedIceQueue.poll());
                }
                if (trackedIceQueue.isEmpty()) {
                    trackedIce.clear();
                }
                return;
            }

            if (!chunksPrepared[0]) {
                collectSweepChunks(chunkQueue);
                chunksPrepared[0] = true;
                plugin.getLogger().info("Cleanup: scanning " + chunkQueue.size() + " chunks (depth " + scanDepth + ").");
                if (chunkQueue.isEmpty()) {
                    finishMelt();
                }
                return;
            }

            for (int i = 0; i < chunksPerTick && !chunkQueue.isEmpty(); i++) {
                long key = chunkQueue.poll();
                int cx = (int) (key >> 32);
                int cz = (int) key;
                // A chunk that was never generated cannot contain any snow we
                // (or vanilla) ever placed there. Skip it instead of calling
                // getChunkAt(), which would otherwise SYNCHRONOUSLY GENERATE
                // brand-new terrain on the main thread just to scan it — a
                // needless lag spike, and it would permanently expand the map.
                if (!world.isChunkGenerated(cx, cz)) continue;
                world.addPluginChunkTicket(cx, cz, plugin);
                world.getChunkAt(cx, cz);
                sweepChunk(cx, cz, scanDepth);
                world.removePluginChunkTicket(cx, cz, plugin);
            }

            if (chunkQueue.isEmpty()) {
                finishMelt();
            }
        }, 0L, ticksBetween);
    }

    private void finishMelt() {
        cancel(meltTask);
        meltTask = null;
        trackedSnow.clear();
        preexistingSnow.clear();
        trackedIce.clear();
        preexistingIce.clear();
        cleaningUp = false;
        releaseChunkTickets();
        saveState();
        Bukkit.broadcastMessage("§a❄ Snow has melted, grass is back to normal.");
    }

    /** Removes the entire snow layer at once and fixes snowy below. */
    private void removeSnowFully(SnowPos p) {
        // Never remove snow that was already there before winter started —
        // even if vanilla weather grew an extra layer on top of it during
        // the event (which is what put it in the tracked-snow queue).
        if (preexistingSnow.contains(p)) return;
        Block block = world.getBlockAt(p.x(), p.y(), p.z());
        if (block.getType() == Material.SNOW) {
            block.setType(Material.AIR, false);
        }
        clearSnowyBelow(block);
    }

    /** Melts a single tracked ICE block back to a water source. */
    private void meltIceFully(SnowPos p) {
        // Never melt ice that was already there before winter started
        // (a naturally frozen river/lake) — mirrors removeSnowFully().
        if (preexistingIce.contains(p)) return;
        Block block = world.getBlockAt(p.x(), p.y(), p.z());
        if (block.getType() == Material.ICE) {
            block.setType(Material.WATER, false);
        }
    }

    /** Clears the snowy flag on grass/podzol/mycelium under removed snow. */
    private void clearSnowyBelow(Block snowOrAirBlock) {
        Block below = snowOrAirBlock.getRelative(BlockFace.DOWN);
        BlockData data = below.getBlockData();
        if (data instanceof Snowable snowable && snowable.isSnowy()) {
            snowable.setSnowy(false);
            below.setBlockData(snowable, false);
        }
    }

    /** Queue of chunks intersecting the zone (+ padding) — without loading them upfront. */
    private void collectSweepChunks(Deque<Long> out) {
        if (world == null) return;
        int sweepRadius = radius + ZONE_PADDING;
        int minCx = (centerX - sweepRadius) >> 4;
        int maxCx = (centerX + sweepRadius) >> 4;
        int minCz = (centerZ - sweepRadius) >> 4;
        int maxCz = (centerZ + sweepRadius) >> 4;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!chunkIntersectsZone(cx, cz, sweepRadius)) continue;
                out.add(chunkKey(cx, cz));
            }
        }
    }

    private boolean chunkIntersectsZone(int cx, int cz, int sweepRadius) {
        int minX = cx << 4;
        int minZ = cz << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        // Nearest point of the chunk to the center.
        int nearestX = clamp(centerX, minX, maxX);
        int nearestZ = clamp(centerZ, minZ, maxZ);
        long dx = nearestX - centerX;
        long dz = nearestZ - centerZ;
        return dx * dx + dz * dz <= (long) sweepRadius * (long) sweepRadius;
    }

    private void sweepChunk(int cx, int cz, int scanDepth) {
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                if (!isInsideRadius(x, z, ZONE_PADDING)) continue;
                sweepColumn(x, z, scanDepth);
            }
        }
    }

    /**
     * Scans a column from the surface (HeightMap.WORLD_SURFACE) downward by scanDepth.
     * Enough for snow on ground, roofs and leaves — without walking the full world Y.
     */
    private void sweepColumn(int x, int z, int scanDepth) {
        int top = Math.min(maxY, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE));
        int bottom = Math.max(minY, top - scanDepth);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();

            if (type == Material.SNOW) {
                // Natural snow that predates the event (mountains, an
                // existing snowy biome, ...) is left exactly as it was.
                if (!preexistingSnow.contains(new SnowPos(x, y, z))) {
                    block.setType(Material.AIR, false);
                    clearSnowyBelow(block);
                }
                continue;
            }

            if (type == Material.ICE) {
                // Safety-net sweep for ice that vanilla formed but we never
                // tracked (e.g. state loss across a crash). Never touches
                // ice that predates the event (frozen river/lake, etc.).
                if (!preexistingIce.contains(new SnowPos(x, y, z))) {
                    block.setType(Material.WATER, false);
                }
                continue;
            }

            if (block.getBlockData() instanceof Snowable snowable && snowable.isSnowy()) {
                Block above = block.getRelative(BlockFace.UP);
                Material aboveType = above.getType();
                if (aboveType != Material.SNOW && aboveType != Material.SNOW_BLOCK) {
                    snowable.setSnowy(false);
                    block.setBlockData(snowable, false);
                }
            }
        }
    }

    // =================================================================
    //  Catch-up for chunks that load AFTER the event started
    // =================================================================

    /** Called by the listener on ChunkLoadEvent. */
    public void onChunkLoaded(Chunk chunk) {
        if (cleaningUp && world != null && chunk.getWorld().equals(world)) {
            sweepChunk(chunk.getX(), chunk.getZ(), getMeltScanDepth());
        }

        if (!active || world == null || !chunk.getWorld().equals(world)) return;

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        int sampleY = clamp(world.getSeaLevel(), minY, maxY);
        int scanDepth = getMeltScanDepth();

        // Unloaded chunks never received event weather ticks, so any snow/ice
        // here is natural (or leftover from before). Snapshot the whole chunk
        // once before painting — not only every-4 biome sample columns.
        boolean anyNewColumn = false;
        for (int x = baseX; x < baseX + 16; x += 4) {
            for (int z = baseZ; z < baseZ + 16; z += 4) {
                long dx = x - centerX;
                long dz = z - centerZ;
                if (dx * dx + dz * dz > (long) radius * (long) radius) continue;
                if (!originalBiomes.containsKey(new ColumnPos(x, z))) {
                    anyNewColumn = true;
                    break;
                }
            }
            if (anyNewColumn) break;
        }
        if (anyNewColumn) {
            snapshotPreexistingInChunk(chunk.getX(), chunk.getZ(), scanDepth);
        }

        for (int x = baseX; x < baseX + 16; x += 4) {
            for (int z = baseZ; z < baseZ + 16; z += 4) {
                long dx = x - centerX;
                long dz = z - centerZ;
                if (dx * dx + dz * dz > (long) radius * (long) radius) continue;

                ColumnPos c = new ColumnPos(x, z);
                if (originalBiomes.containsKey(c)) continue; // already painted earlier

                originalBiomes.put(c, world.getBiome(x, sampleY, z));
                paintColumn(c, targetBiome);
            }
        }
        // Chunk is loading now, so no refresh needed —
        // the client will receive it with the correct data anyway.
    }

    // =================================================================
    //  Persistence (winter-state.yml)
    // =================================================================

    public void loadPersistedState() {
        if (!stateFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        active = yaml.getBoolean("active", false);
        cleaningUp = yaml.getBoolean("cleaning-up", false);
        String worldName = yaml.getString("world", "");
        if (worldName == null || worldName.isBlank()) return;
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("winter-state.yml points to world '" + worldName + "' which is missing. Ignoring saved state.");
            active = false;
            cleaningUp = false;
            return;
        }
        centerX = yaml.getInt("center-x");
        centerZ = yaml.getInt("center-z");
        radius = yaml.getInt("radius");
        minY = yaml.getInt("min-y");
        maxY = yaml.getInt("max-y");
        weatherForcedByUs = yaml.getBoolean("weather-forced-by-us", false);
        targetBiome = resolveBiome(yaml.getString("target-biome", "minecraft:snowy_taiga"));

        originalBiomes.clear();
        var columnsSection = yaml.getConfigurationSection("columns");
        if (columnsSection != null) {
            for (String key : columnsSection.getKeys(false)) {
                String[] parts = key.split(",");
                ColumnPos c = new ColumnPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                Biome b = resolveBiome(columnsSection.getString(key));
                if (b != null) originalBiomes.put(c, b);
            }
        }

        trackedSnow.clear();
        for (String entry : yaml.getStringList("snow-blocks")) {
            String[] parts = entry.split(",");
            trackedSnow.add(new SnowPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }

        preexistingSnow.clear();
        for (String entry : yaml.getStringList("preexisting-snow-blocks")) {
            String[] parts = entry.split(",");
            preexistingSnow.add(new SnowPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }

        trackedIce.clear();
        for (String entry : yaml.getStringList("ice-blocks")) {
            String[] parts = entry.split(",");
            trackedIce.add(new SnowPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }

        preexistingIce.clear();
        for (String entry : yaml.getStringList("preexisting-ice-blocks")) {
            String[] parts = entry.split(",");
            preexistingIce.add(new SnowPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        }

        if (active) {
            plugin.getLogger().info("Interrupted winter event detected — resuming painting/cleanup in the background.");
            resumeAfterRestart();
        } else if (cleaningUp || !originalBiomes.isEmpty() || !trackedSnow.isEmpty() || !trackedIce.isEmpty()) {
            plugin.getLogger().info("Incomplete post-winter cleanup detected — finishing in the background.");
            var cfg = plugin.getConfig();
            cleaningUp = true;
            if (!originalBiomes.isEmpty()) {
                Deque<ColumnPos> restoreQueue = new ArrayDeque<>(originalBiomes.keySet());
                int columnsPerBatch = getPaintColumnsPerBatch();
                long ticksBetween = getPaintTicksBetweenBatches();
                restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    Set<Long> touchedChunks = new HashSet<>();
                    for (int i = 0; i < columnsPerBatch && !restoreQueue.isEmpty(); i++) {
                        ColumnPos c = restoreQueue.poll();
                        Biome original = originalBiomes.remove(c);
                        if (original != null) {
                            paintColumn(c, original);
                            touchedChunks.add(chunkKey(c.x() >> 4, c.z() >> 4));
                        }
                    }
                    refreshChunks(touchedChunks);
                    if (restoreQueue.isEmpty()) {
                        cancel(restoreTask);
                        restoreTask = null;
                        saveState();
                    }
                }, 20L, ticksBetween);
            }
            startMeltAndSweep(cfg);
        }
    }

    /** After a restart mid-event: repaint anything that was not finished. */
    private void resumeAfterRestart() {
        Deque<ColumnPos> paintQueue = new ArrayDeque<>();
        collectColumnsInZone(paintQueue);
        // Columns already in originalBiomes are painted (or in progress) —
        // repainting ALL of them is a safe, idempotent operation.
        int sampleY = clamp(world.getSeaLevel(), minY, maxY);
        for (ColumnPos c : paintQueue) {
            originalBiomes.putIfAbsent(c, world.getBiome(c.x(), sampleY, c.z()));
        }
        saveState();

        int columnsPerBatch = getPaintColumnsPerBatch();
        long ticksBetween = getPaintTicksBetweenBatches();
        paintTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            processPaintBatch(paintQueue, columnsPerBatch);
            if (paintQueue.isEmpty()) {
                cancel(paintTask);
                paintTask = null;
                saveState();
            }
        }, 20L, ticksBetween);
    }

    public void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("active", active);
        yaml.set("cleaning-up", cleaningUp);
        yaml.set("world", world == null ? "" : world.getName());
        yaml.set("center-x", centerX);
        yaml.set("center-z", centerZ);
        yaml.set("radius", radius);
        yaml.set("min-y", minY);
        yaml.set("max-y", maxY);
        yaml.set("weather-forced-by-us", weatherForcedByUs);
        yaml.set("target-biome", targetBiome == null ? "minecraft:snowy_taiga" : keyOf(targetBiome));

        Map<String, Object> columns = new HashMap<>();
        for (Map.Entry<ColumnPos, Biome> e : originalBiomes.entrySet()) {
            columns.put(e.getKey().x() + "," + e.getKey().z(), keyOf(e.getValue()));
        }
        yaml.set("columns", columns);

        List<String> snow = new ArrayList<>();
        for (SnowPos p : trackedSnow) {
            snow.add(p.x() + "," + p.y() + "," + p.z());
        }
        yaml.set("snow-blocks", snow);

        List<String> preexisting = new ArrayList<>();
        for (SnowPos p : preexistingSnow) {
            preexisting.add(p.x() + "," + p.y() + "," + p.z());
        }
        yaml.set("preexisting-snow-blocks", preexisting);

        List<String> ice = new ArrayList<>();
        for (SnowPos p : trackedIce) {
            ice.add(p.x() + "," + p.y() + "," + p.z());
        }
        yaml.set("ice-blocks", ice);

        List<String> preexistingIceList = new ArrayList<>();
        for (SnowPos p : preexistingIce) {
            preexistingIceList.add(p.x() + "," + p.y() + "," + p.z());
        }
        yaml.set("preexisting-ice-blocks", preexistingIceList);

        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save winter-state.yml", e);
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================

    private boolean resolveZoneFromConfig(CommandSender sender) {
        var cfg = plugin.getConfig();
        String worldName = cfg.getString("zone.world", "");
        if (worldName == null || worldName.isBlank()) {
            sender.sendMessage("§cZone center is not set. Use first: /winter setcenter <radius>");
            return false;
        }
        World resolved = Bukkit.getWorld(worldName);
        if (resolved == null) {
            sender.sendMessage("§cWorld '" + worldName + "' from config does not exist / is not loaded.");
            return false;
        }
        applyZoneBoundsFromConfig(resolved);
        return true;
    }

    /**
     * Reads center/radius/min-y/max-y from config.yml and applies them to the
     * given (already resolved) world. Shared by start() and cleanup(), so the
     * two never drift apart.
     */
    private void applyZoneBoundsFromConfig(World resolvedWorld) {
        var cfg = plugin.getConfig();
        this.world = resolvedWorld;
        this.centerX = cfg.getInt("zone.center-x", 0);
        this.centerZ = cfg.getInt("zone.center-z", 0);
        this.radius = Math.max(4, cfg.getInt("zone.radius", 80));
        // -1 means "auto-detect world limits". If you ever need the zone
        // floor/ceiling to be EXACTLY Y=-1, use a different value (e.g. -2)
        // and adjust — -1 is reserved as the "auto" sentinel here.
        int cfgMinY = cfg.getInt("zone.min-y", -1);
        int cfgMaxY = cfg.getInt("zone.max-y", -1);
        this.minY = cfgMinY == -1 ? world.getMinHeight() : cfgMinY;
        this.maxY = cfgMaxY == -1 ? world.getMaxHeight() - 1 : cfgMaxY;
    }

    private void collectColumnsInZone(Deque<ColumnPos> out) {
        for (int x = centerX - radius; x <= centerX + radius; x += 4) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += 4) {
                long dx = x - centerX;
                long dz = z - centerZ;
                if (dx * dx + dz * dz > (long) radius * (long) radius) continue;
                // Do not force-generate/load new chunks —
                // unloaded zone fragments are painted lazily via onChunkLoaded()
                // when someone actually walks there.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                out.add(new ColumnPos(x, z));
            }
        }
    }

    private void refreshChunks(Set<Long> chunkKeys) {
        for (long key : chunkKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            // World#refreshChunk is @Deprecated, but still the only public-API way
            // to force an immediate biome refresh for already logged-in players
            // (otherwise they only see it after re-entering the chunk / relogging).
            // Alternative: hand-crafted packets (ProtocolLib/NMS) — out of scope here.
            @SuppressWarnings("deprecation")
            boolean ignored = world.refreshChunk(cx, cz);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @SuppressWarnings("deprecation")
    private static Biome resolveBiome(String key) {
        if (key == null) return null;
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        if (namespacedKey == null) return null;
        return Registry.BIOME.get(namespacedKey);
    }

    @SuppressWarnings("deprecation")
    private static String keyOf(Biome biome) {
        return biome.getKey().toString();
    }

    // =================================================================
    //  Zone center config (/winter setcenter)
    // =================================================================

    public void setCenter(CommandSender sender, World world, int x, int z, int radius) {
        var cfg = plugin.getConfig();
        cfg.set("zone.world", world.getName());
        cfg.set("zone.center-x", x);
        cfg.set("zone.center-z", z);
        cfg.set("zone.radius", radius);
        plugin.saveConfig();
        sender.sendMessage("§aZone center set to (" + x + ", " + z + ") in world '" + world.getName()
                + "' with radius " + radius + ". Use /winter start to begin the event.");
    }
}
