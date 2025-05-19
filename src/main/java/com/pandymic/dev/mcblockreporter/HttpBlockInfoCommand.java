package com.pandymic.dev.mcblockreporter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

public class HttpBlockInfoCommand implements CommandExecutor {

    private final McBlockReporterPlugin plugin;

    public HttpBlockInfoCommand(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("httpblockinfo")) {
            if (args.length < 3 || args.length > 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /httpblockinfo <x> <y> <z> [extraData|@selector]");
                return true;
            }

            try {
                int x = Integer.parseInt(args[0]);
                int y = Integer.parseInt(args[1]);
                int z = Integer.parseInt(args[2]);
                Object extraData = null;

                if (args.length == 4) {
                    String potentialSelector = args[3];
                    if (potentialSelector.startsWith("@")) {
                        List<Entity> targets = Bukkit.selectEntities(sender, potentialSelector);
                        if (!targets.isEmpty()) {
                            extraData = targets.stream().map(Entity::getName).collect(Collectors.toList());
                        } else {
                            extraData = potentialSelector;
                            sender.sendMessage(ChatColor.YELLOW + "Target selector '" + potentialSelector + "' did not match any entities. Sending as a string.");
                        }
                    } else {
                        extraData = potentialSelector;
                    }
                }

                Location location;
                if (sender instanceof Player) {
                    location = new Location(((Player) sender).getWorld(), x, y, z);
                } else {
                    location = new Location(plugin.getServer().getWorlds().get(0), x, y, z);
                }

                plugin.sendBlockData(location, extraData);
                sender.sendMessage(ChatColor.GREEN + "Retrieving and sending block information for " + x + ", " + y + ", " + z + (extraData != null ? " with extra data: " + extraData : "") + "...");
                return true;

            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid coordinates. Please enter numbers for x, y, and z.");
                return true;
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "An error occurred: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error processing httpblockinfo command:", e);
                return true;
            }
        }
        return false;
    }
}
