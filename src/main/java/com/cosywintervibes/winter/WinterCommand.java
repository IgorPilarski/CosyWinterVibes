package com.cosywintervibes.winter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /winter start|stop|status|setcenter &lt;radius&gt;|cleanup
 * Permission "cosywintervibes.admin" is already enforced by plugin.yml.
 */
public final class WinterCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("start", "stop", "status", "setcenter", "cleanup");

    private final WinterZoneManager manager;

    public WinterCommand(WinterZoneManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7Usage: /winter <start|stop|status|setcenter|cleanup>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> manager.start(sender);
            case "stop" -> manager.stop(sender);
            case "status" -> sender.sendMessage(manager.isActive()
                    ? "§b❄ Winter is currently ACTIVE at the base."
                    : "§7Winter is currently disabled.");
            case "setcenter" -> handleSetCenter(sender, args);
            case "cleanup" -> manager.cleanup(sender);
            default -> sender.sendMessage("§cUnknown subcommand. Usage: /winter <start|stop|status|setcenter|cleanup>");
        }
        return true;
    }

    private void handleSetCenter(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used as a player (uses your position as the center).");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /winter setcenter <radius>");
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
            sender.sendMessage("§cCannot change the zone center while winter is active. Use /winter stop first.");
            return;
        }
        manager.setCenter(sender, player.getWorld(), player.getLocation().getBlockX(),
                player.getLocation().getBlockZ(), radius);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS;
        }
        return List.of();
    }
}
