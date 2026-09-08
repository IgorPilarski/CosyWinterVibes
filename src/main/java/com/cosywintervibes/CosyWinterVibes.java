package com.cosywintervibes;

import com.cosywintervibes.winter.WinterCommand;
import com.cosywintervibes.winter.WinterWeatherListener;
import com.cosywintervibes.winter.WinterZoneManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class. All winter logic lives in {@link WinterZoneManager};
 * this class only wires components and manages the enable/disable lifecycle,
 * including restoring state after a server restart/crash during an active event.
 */
public final class CosyWinterVibes extends JavaPlugin {

    private WinterZoneManager zoneManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.zoneManager = new WinterZoneManager(this);
        // Loads winter-state.yml (if present) and — if the event was active
        // when the server shut down — safely resumes painting/restoring/melting
        // from where it left off.
        this.zoneManager.loadPersistedState();

        getServer().getPluginManager().registerEvents(new WinterWeatherListener(zoneManager), this);

        WinterCommand command = new WinterCommand(zoneManager);
        getCommand("winter").setExecutor(command);
        getCommand("winter").setTabCompleter(command);

        getLogger().info("CosyWinterVibes enabled. Winter active: " + zoneManager.isActive());
    }

    @Override
    public void onDisable() {
        if (zoneManager != null) {
            // No forced finish here — state is saved to disk continuously
            // (after each operation batch), so the plugin simply stops
            // processing queues and picks them up again on next startup.
            zoneManager.cancelRunningTasks();
        }
    }
}
