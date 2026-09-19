package com.pandymic.dev.mcblockreporter;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /registerlandmark [x y z] [label]
 *
 * Registers a named point with the web service's landmark list (see
 * McBlockReporterPlugin#registerLandmark). With no coordinates, a player
 * registers their own current position; console senders must supply
 * coordinates explicitly. Unlike /registerblockmonitor, there's no
 * "block a player is looking at" default -- a landmark is a place, not a
 * specific block.
 */
public class RegisterLandmarkCommand implements CommandExecutor {

    private final McBlockReporterPlugin plugin;

    public RegisterLandmarkCommand(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Location location;
        String landmarkLabel = null;

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
                    landmarkLabel = args[3];
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
            location = ((Player) sender).getLocation();
            if (args.length == 1) {
                landmarkLabel = args[0];
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [x y z] [label]");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Registering landmark at "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "...");
        plugin.registerLandmark(location, landmarkLabel, sender);
        return true;
    }
}
