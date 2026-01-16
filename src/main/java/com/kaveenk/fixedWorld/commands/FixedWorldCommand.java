package com.kaveenk.fixedWorld.commands;

import com.kaveenk.fixedWorld.managers.ChunkScanner;
import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles /fixedworld commands.
 */
public class FixedWorldCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("fix", "unfix", "status", "absolute", "batchsize");
    private static final List<String> DELAY_SUGGESTIONS = List.of("5", "10", "30", "60", "300");
    private static final List<String> BATCH_SUGGESTIONS = List.of("25", "50", "100", "200");
    private static final List<String> ON_OFF = List.of("on", "off");

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

        switch (args[0].toLowerCase()) {
            case "fix" -> handleFix(player, args);
            case "unfix" -> handleUnfix(player);
            case "status" -> handleStatus(player);
            case "absolute" -> handleAbsolute(player, args);
            case "batchsize" -> handleBatchSize(player, args);
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

        if (seconds < 1 || seconds > 3600) {
            player.sendMessage(ChatColor.RED + "Delay must be between 1 and 3600 seconds.");
            return;
        }

        World world = player.getWorld();
        snapshotManager.enableFixedWorld(world, seconds);
        player.sendMessage(ChatColor.GREEN + "Fixed world enabled for '" + world.getName() + "'!");
        player.sendMessage(ChatColor.GRAY + "Blocks will restore " + seconds + " seconds after being changed.");
        
        // Show absolute mode status
        if (snapshotManager.isAbsoluteModeEnabled()) {
            player.sendMessage(ChatColor.AQUA + "Absolute mode is ON - /fill, /setblock, and plugin changes will be caught.");
        } else {
            player.sendMessage(ChatColor.GRAY + "Tip: Use " + ChatColor.YELLOW + "/fixedworld absolute on" + 
                             ChatColor.GRAY + " to catch /fill and plugin changes.");
        }
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
        if (!snapshotManager.isFixedWorld(world)) {
            player.sendMessage(ChatColor.YELLOW + "This world is not currently fixed.");
            return;
        }
        int delay = snapshotManager.getRestoreDelaySeconds(world);
        int pending = snapshotManager.getPendingCount(world);
        boolean absoluteMode = snapshotManager.isAbsoluteModeEnabled();
        
        player.sendMessage(ChatColor.GREEN + "Fixed World Status for '" + world.getName() + "':");
        player.sendMessage(ChatColor.GRAY + "  Restore delay: " + delay + " seconds");
        player.sendMessage(ChatColor.GRAY + "  Pending restorations: " + pending);
        player.sendMessage(ChatColor.GRAY + "  Queue stats: " + snapshotManager.getQueueStats());
        player.sendMessage(ChatColor.GRAY + "  Absolute mode: " + 
                          (absoluteMode ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        
        if (absoluteMode) {
            ChunkScanner scanner = snapshotManager.getChunkScanner();
            if (scanner != null) {
                int chunks = scanner.getBaselineChunkCount(world);
                player.sendMessage(ChatColor.GRAY + "  Baseline chunks: " + chunks);
            }
        }
    }

    private void handleAbsolute(Player player, String[] args) {
        if (args.length < 2) {
            boolean current = snapshotManager.isAbsoluteModeEnabled();
            player.sendMessage(ChatColor.GRAY + "Absolute mode is currently: " + 
                              (current ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            player.sendMessage(ChatColor.GRAY + "Usage: /fixedworld absolute <on|off>");
            return;
        }

        String toggle = args[1].toLowerCase();
        if (toggle.equals("on") || toggle.equals("true") || toggle.equals("enable")) {
            snapshotManager.setAbsoluteModeEnabled(true);
            player.sendMessage(ChatColor.GREEN + "Absolute mode " + ChatColor.BOLD + "ENABLED");
            player.sendMessage(ChatColor.GRAY + "ChunkScanner will now detect /fill, /setblock, and plugin changes.");
            player.sendMessage(ChatColor.GRAY + "Note: This adds some CPU overhead for periodic chunk scanning.");
        } else if (toggle.equals("off") || toggle.equals("false") || toggle.equals("disable")) {
            snapshotManager.setAbsoluteModeEnabled(false);
            player.sendMessage(ChatColor.YELLOW + "Absolute mode " + ChatColor.BOLD + "DISABLED");
            player.sendMessage(ChatColor.GRAY + "Only event-based block changes will be caught.");
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /fixedworld absolute <on|off>");
        }
    }

    private void handleBatchSize(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "Batch size controls how many blocks restore per tick.");
            player.sendMessage(ChatColor.GRAY + "Higher = faster but more lag potential.");
            player.sendMessage(ChatColor.GRAY + "Usage: /fixedworld batchsize <blocks>");
            return;
        }

        int blocks;
        try {
            blocks = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
            return;
        }

        if (blocks < 1 || blocks > 1000) {
            player.sendMessage(ChatColor.RED + "Batch size must be between 1 and 1000.");
            return;
        }

        snapshotManager.setBlocksPerTick(blocks);
        player.sendMessage(ChatColor.GREEN + "Batch size set to " + blocks + " blocks per tick.");
        
        // Helpful context
        if (blocks <= 25) {
            player.sendMessage(ChatColor.GRAY + "Low batch size - very smooth but slower restoration.");
        } else if (blocks <= 100) {
            player.sendMessage(ChatColor.GRAY + "Balanced batch size - good for most servers.");
        } else {
            player.sendMessage(ChatColor.GRAY + "High batch size - fast restoration, monitor for lag.");
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== FixedWorld Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld fix <seconds>" + ChatColor.GRAY + " - Enable fixed world");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld unfix" + ChatColor.GRAY + " - Disable fixed world");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld status" + ChatColor.GRAY + " - Show status");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld absolute <on|off>" + ChatColor.GRAY + " - Toggle absolute mode");
        player.sendMessage(ChatColor.YELLOW + "/fixedworld batchsize <n>" + ChatColor.GRAY + " - Set blocks per tick");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUB_COMMANDS.stream().filter(s -> s.startsWith(partial)).toList();
        }
        if (args.length == 2) {
            String subCmd = args[0].toLowerCase();
            if (subCmd.equals("fix")) {
                return new ArrayList<>(DELAY_SUGGESTIONS);
            }
            if (subCmd.equals("absolute")) {
                String partial = args[1].toLowerCase();
                return ON_OFF.stream().filter(s -> s.startsWith(partial)).toList();
            }
            if (subCmd.equals("batchsize")) {
                return new ArrayList<>(BATCH_SUGGESTIONS);
            }
        }
        return List.of();
    }
}
