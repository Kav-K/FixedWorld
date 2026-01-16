package com.kaveenk.fixedWorld.commands;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles /fixedworld commands.
 * Usage:
 *   /fixedworld fix <seconds> - Enable fixed world with restore delay
 *   /fixedworld unfix - Disable fixed world
 *   /fixedworld status - Show current status
 */
public class FixedWorldCommand implements CommandExecutor, TabCompleter {

    private final WorldSnapshotManager snapshotManager;

    public FixedWorldCommand(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("fixedworld.admin")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "fix" -> handleFix(player, args);
            case "unfix" -> handleUnfix(player);
            case "status" -> handleStatus(player);
            default -> sendUsage(player);
        }

        return true;
    }

    private void handleFix(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Usage: /fixedworld fix <seconds>");
            return;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
            return;
        }

        if (seconds < 1) {
            player.sendMessage(ChatColor.RED + "Delay must be at least 1 second.");
            return;
        }

        if (seconds > 3600) {
            player.sendMessage(ChatColor.RED + "Delay cannot exceed 3600 seconds (1 hour).");
            return;
        }

        World world = player.getWorld();
        snapshotManager.enableFixedWorld(world, seconds);

        player.sendMessage(ChatColor.GREEN + "Fixed world enabled for '" + world.getName() + "'!");
        player.sendMessage(ChatColor.GRAY + "Blocks will restore " + seconds + " seconds after being changed.");
    }

    private void handleUnfix(Player player) {
        World world = player.getWorld();

        if (!snapshotManager.isFixedWorld(world)) {
            player.sendMessage(ChatColor.YELLOW + "This world is not currently fixed.");
            return;
        }

        snapshotManager.disableFixedWorld(world);
        player.sendMessage(ChatColor.GREEN + "Fixed world disabled for '" + world.getName() + "'.");
    }

    private void handleStatus(Player player) {
        World world = player.getWorld();

        if (snapshotManager.isFixedWorld(world)) {
            int delay = snapshotManager.getRestoreDelaySeconds(world);
            int pending = snapshotManager.getPendingCount(world);
            player.sendMessage(ChatColor.GREEN + "Fixed World Status for '" + world.getName() + "':");
            player.sendMessage(ChatColor.GRAY + "  Restore delay: " + delay + " seconds");
            player.sendMessage(ChatColor.GRAY + "  Pending restorations: " + pending);
        } else {
            player.sendMessage(ChatColor.YELLOW + "This world is not currently fixed.");
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== FixedWorld Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld fix <seconds>" + ChatColor.GRAY + " - Enable fixed world");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld unfix" + ChatColor.GRAY + " - Disable fixed world");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld status" + ChatColor.GRAY + " - Show status");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("fix", "unfix", "status");
            String partial = args[0].toLowerCase();
            for (String sub : subCommands) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("fix")) {
            // Suggest common delay values
            completions.addAll(Arrays.asList("5", "10", "30", "60", "300"));
        }

        return completions;
    }
}
