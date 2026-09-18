package com.pandymic.dev.mcblockreporter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * /scanrailnetwork <world> <x> <y|~> <z> [label]
 *
 * Always takes explicit coordinates (including world) rather than defaulting
 * to a player's position -- this command is primarily triggered by the web
 * service injecting it via console (see McBlockReporterPlugin#scanRailNetwork
 * and RegisterBlockMonitorCommand for the same console-sender consideration),
 * not typed by a player standing at the track. `~` for Y means "search the
 * full world height outward from the surface" -- see RailNetworkScanner.
 */
public class ScanRailNetworkCommand implements CommandExecutor {

    private final McBlockReporterPlugin plugin;

    public ScanRailNetworkCommand(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 4 || args.length > 5) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <world> <x> <y|~> <z> [label]");
            return true;
        }

        World world = Bukkit.getWorld(args[0]);
        if (null == world) {
            sender.sendMessage(ChatColor.RED + "Unknown world '" + args[0] + "'.");
            return true;
        }

        try {
            int x = Integer.parseInt(args[1]);
            Integer y = "~".equals(args[2]) ? null : Integer.valueOf(args[2]);
            int z = Integer.parseInt(args[3]);
            String networkLabel = args.length == 5 ? args[4] : null;

            sender.sendMessage(ChatColor.YELLOW + "Scanning rail network from "
                    + x + "," + (null != y ? y : "~") + "," + z + " in " + world.getName() + "...");

            RailNetworkScanner.Result result = RailNetworkScanner.scan(world, x, y, z);
            if (null != result.error) {
                sender.sendMessage(ChatColor.RED + result.error);
                return true;
            }

            sender.sendMessage(ChatColor.GREEN + "Mapped " + result.nodes.size() + " rail block(s)"
                    + (result.truncated ? " (truncated at scan limit)" : "") + ". Reporting to service...");
            plugin.reportRailNetworkScan(world.getName(), x, y, z, networkLabel, result, sender);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid coordinates. Usage: /" + label + " <world> <x> <y|~> <z> [label]");
        }
        return true;
    }
}
