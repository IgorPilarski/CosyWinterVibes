package com.cosywintervibes.winter;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /winter start|stop|status|setcenter|cleanup|freeze|help
 * Permission "cosywintervibes.admin" is already enforced by plugin.yml.
 */
public final class WinterCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("start", "stop", "status", "setcenter", "cleanup", "freeze", "help");

    private final WinterZoneManager manager;

    public WinterCommand(WinterZoneManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "?" -> sendHelp(sender, label);
            case "start" -> manager.start(sender);
            case "stop" -> manager.stop(sender);
            case "status" -> sendStatus(sender);
            case "setcenter" -> handleSetCenter(sender, label, args);
            case "cleanup" -> manager.cleanup(sender);
            case "freeze" -> handleFreeze(sender, label, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(manager.isActive()
                ? "§b❄ Winter is currently ACTIVE at the base."
                : "§7Winter is currently disabled.");
        sender.sendMessage(manager.isFreezeSurfaceWaterEnabled()
                ? "§bSurface water freezing: §aON§7 (open water may freeze; covered water stays liquid)."
                : "§bSurface water freezing: §cOFF§7 (all ice cancelled in the zone).");
    }

    /**
     * /winter freeze — show current setting
     * /winter freeze on|off — persist to config.yml (takes effect for new ice immediately)
     */
    private void handleFreeze(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(manager.isFreezeSurfaceWaterEnabled()
                    ? "§bSurface water freezing is §aON§7. Use /" + label + " freeze off to disable."
                    : "§bSurface water freezing is §cOFF§7. Use /" + label + " freeze on to enable.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("§cUsage: /" + label + " freeze [on|off]");
            return;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (value.equals("on") || value.equals("true") || value.equals("enable")) {
            manager.setFreezeSurfaceWater(sender, true);
        } else if (value.equals("off") || value.equals("false") || value.equals("disable")) {
            manager.setFreezeSurfaceWater(sender, false);
        } else {
            sender.sendMessage("§cUsage: /" + label + " freeze [on|off]");
        }
    }

    /**
     * /winter setcenter &lt;radius&gt;
     * /winter setcenter &lt;radius&gt; &lt;x&gt; &lt;z&gt;
     * Without coordinates, uses the player's current position.
     */
    private void handleSetCenter(CommandSender sender, String label, String[] args) {
        if (args.length != 2 && args.length != 4) {
            sender.sendMessage("§cUsage: /" + label + " setcenter <radius> [x z]");
            return;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cRadius must be an integer.");
            return;
        }
        if (radius < 4) {
            sender.sendMessage("§cMinimum radius is 4 blocks.");
            return;
        }
        if (manager.isActive()) {
            sender.sendMessage("§cCannot change the zone center while winter is active. Use /" + label + " stop first.");
            return;
        }

        World world;
        int x;
        int z;

        if (args.length == 4) {
            try {
                x = Integer.parseInt(args[2]);
                z = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cCoordinates must be integers.");
                return;
            }
            if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                world = resolveConfiguredOrDefaultWorld();
                if (world == null) {
                    sender.sendMessage("§cNo world available. Join as a player or set zone.world in config.yml.");
                    return;
                }
            }
        } else {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cConsole must provide coordinates: /" + label + " setcenter <radius> <x> <z>");
                return;
            }
            world = player.getWorld();
            x = player.getLocation().getBlockX();
            z = player.getLocation().getBlockZ();
        }

        manager.setCenter(sender, world, x, z, radius);
    }

    private World resolveConfiguredOrDefaultWorld() {
        String configured = manager.getPlugin().getConfig().getString("zone.world", "");
        if (configured != null && !configured.isBlank()) {
            World world = Bukkit.getWorld(configured);
            if (world != null) {
                return world;
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6=== CosyWinterVibes - Help ===");
        sender.sendMessage("§e/" + label + " setcenter <radius> [x z]§7 - set zone center (omit x z to use your position)");
        sender.sendMessage("§e/" + label + " start§7 - start the winter event");
        sender.sendMessage("§e/" + label + " stop§7 - stop winter, restore biomes, clean snow");
        sender.sendMessage("§e/" + label + " status§7 - show whether winter is active");
        sender.sendMessage("§e/" + label + " cleanup§7 - clean leftover snow / white grass");
        sender.sendMessage("§e/" + label + " freeze [on|off]§7 - allow ice on open surface water (omit arg to show)");
        sender.sendMessage("§e/" + label + " help§7 - show this help");
        sender.sendMessage("§aExample: /" + label + " setcenter 80");
        sender.sendMessage("§aExample: /" + label + " setcenter 80 100 -200");
        sender.sendMessage("§aExample: /" + label + " freeze on");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            for (String option : SUBCOMMANDS) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("freeze")) {
            String input = args[1].toLowerCase(Locale.ROOT);
            for (String option : List.of("on", "off")) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        }
        return completions;
    }
}
