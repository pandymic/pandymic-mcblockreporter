package com.pandymic.dev.mcblockreporter;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /registerblockmonitor [x y z] [label]
 *
 * Registers a block with the web service's monitored-block registry (see
 * McBlockReporterPlugin#registerMonitoredBlock). With no coordinates, a
 * player registers whichever block they're looking at; console senders must
 * supply coordinates explicitly.
 */
public class RegisterBlockMonitorCommand implements CommandExecutor {

    private static final int TARGET_BLOCK_MAX_DISTANCE = 16;

    private final McBlockReporterPlugin plugin;

    public RegisterBlockMonitorCommand(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Location location;
        String monitorLabel = null;

        if (args.length >= 3) {
            try {
                int x = Integer.parseInt(args[0]);
                int y = Integer.parseInt(args[1]);
                int z = Integer.parseInt(args[2]);

                World world = (sender instanceof Player)
                        ? ((Player) sender).getWorld()
                        : plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
                if (world == null) {
                    sender.sendMessage(ChatColor.RED + "No world available to resolve coordinates.");
                    return true;
                }
                location = new Location(world, x, y, z);
                if (args.length >= 4) {
                    monitorLabel = args[3];
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid coordinates. Usage: /" + label + " [x y z] [label]");
                return true;
            }
        } else if (args.length <= 1) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify coordinates. Usage: /" + label + " <x> <y> <z> [label]");
                return true;
            }
            Player player = (Player) sender;
            Block targetBlock = player.getTargetBlockExact(TARGET_BLOCK_MAX_DISTANCE);
            if (targetBlock == null) {
                sender.sendMessage(ChatColor.RED + "No block in sight within " + TARGET_BLOCK_MAX_DISTANCE + " blocks. Look at a block or specify coordinates.");
                return true;
            }
            location = targetBlock.getLocation();
            if (args.length == 1) {
                monitorLabel = args[0];
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [x y z] [label]");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Registering block at " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "...");
        plugin.registerMonitoredBlock(location, monitorLabel, sender);
        return true;
    }
}
