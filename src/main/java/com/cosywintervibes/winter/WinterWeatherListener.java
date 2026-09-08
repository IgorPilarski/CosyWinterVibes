package com.cosywintervibes.winter;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * All safe snow / ice handling lives in this single handler — no custom
 * block-picking loop. Vanilla weather already randomly tries to form snow/ice
 * on suitable, sky-exposed blocks each tick; we only:
 *   1) by default cancel ALL ice formation inside the zone (so water never
 *      freezes); optionally (freeze-surface-water=true) allow vanilla ICE
 *      only on truly exposed surface water — water hidden under any block
 *      (e.g. a bottom slab over a farm) never freezes,
 *   2) always cancel FROSTED_ICE (Frost Walker) — we never want that mechanic,
 *   3) cap the maximum natural snow-layer height,
 *   4) remember every snow/ice block vanilla places, so we know exactly what
 *      to clean up after "/winter stop",
 *   5) while cleaning up, cancel NEW snowfall/ice so the sweep is not chasing
 *      freshly formed blocks.
 *
 * Note: EntityBlockFormEvent (e.g. Frost Walker ice) is a subclass of
 * BlockFormEvent, so this same handler covers that case too.
 */
public final class WinterWeatherListener implements Listener {

    private final WinterZoneManager manager;

    public WinterWeatherListener(WinterZoneManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        Material formed = event.getNewState().getType();

        // During cleanup: no new snow/ice inside the zone.
        if (manager.isCleaningUp()) {
            if (!manager.isInsideZone(block.getLocation(), WinterZoneManager.ZONE_PADDING)) return;
            if (formed == Material.SNOW || formed == Material.ICE || formed == Material.FROSTED_ICE) {
                event.setCancelled(true);
            }
            return;
        }

        if (!manager.isActive()) return;
        if (!manager.isInsideZone(block.getLocation(), WinterZoneManager.ZONE_PADDING)) return;

        // Frost Walker ice: never wanted, regardless of freeze-surface-water.
        if (formed == Material.FROSTED_ICE) {
            event.setCancelled(true);
            return;
        }

        if (formed == Material.ICE) {
            if (manager.isFreezeSurfaceWaterEnabled() && isSurfaceExposed(block)) {
                manager.trackIce(block.getX(), block.getY(), block.getZ());
            } else {
                event.setCancelled(true);
            }
            return;
        }

        if (formed == Material.SNOW) {
            if (event.getNewState().getBlockData() instanceof Levelled levelled) {
                int maxAllowed = manager.getMaxSnowLayers();
                if (levelled.getLevel() > maxAllowed) {
                    levelled.setLevel(maxAllowed);
                    event.getNewState().setBlockData(levelled);
                }
            }
            manager.trackSnow(block.getX(), block.getY(), block.getZ());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.onChunkLoaded(event.getChunk());
    }

    /**
     * True only for water that is genuinely open to the sky: nothing (AIR)
     * directly above it, and it actually receives sky light. A block placed
     * over the water (even a bottom slab, which occupies the same block
     * space as far as light/AIR checks are concerned) fails one of these
     * checks, so covered farm water never counts as surface water.
     */
    private boolean isSurfaceExposed(Block block) {
        Block above = block.getRelative(BlockFace.UP);
        return above.getType() == Material.AIR && block.getLightFromSky() > 0;
    }
}
