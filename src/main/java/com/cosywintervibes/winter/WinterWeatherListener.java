package com.cosywintervibes.winter;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * All safe snow / no-ice handling lives in this single handler — no custom
 * block-picking loop. Vanilla weather already randomly tries to form snow/ice
 * on suitable, sky-exposed blocks each tick; we only:
 *   1) cancel ice formation inside the zone (so water never freezes),
 *   2) cap the maximum natural snow-layer height,
 *   3) remember every snow block vanilla places, so we know exactly what to
 *      clean up after "/winter stop",
 *   4) while cleaning up, cancel NEW snowfall so the sweep is not chasing
 *      freshly falling snow.
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

        if (formed == Material.ICE || formed == Material.FROSTED_ICE) {
            event.setCancelled(true);
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
}
