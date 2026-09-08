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
 * /winter start|stop|status|setcenter|cleanup|help
 * Permission "cosywintervibes.admin" is already enforced by plugin.yml.
 */
public final class WinterCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("start", "stop", "status", "setcenter", "cleanup", "help");

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
            case "status" -> sender.sendMessage(manager.isActive()
                    ? "§b❄ Winter is currently ACTIVE at the base."
                    : "§7Winter is currently disabled.");
            case "setcenter" -> handleSetCenter(sender, label, args);
            case "cleanup" -> manager.cleanup(sender);
            default -> sendHelp(sender, label);
        }
        return true;
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
        sender.sendMessage("§e/" + label + " help§7 - show this help");
        sender.sendMessage("§aExample: /" + label + " setcenter 80");
        sender.sendMessage("§aExample: /" + label + " setcenter 80 100 -200");
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
        }
        return completions;
    }
}
