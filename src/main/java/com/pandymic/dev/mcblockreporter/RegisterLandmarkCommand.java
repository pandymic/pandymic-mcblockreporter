package com.pandymic.dev.mcblockreporter;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

/**
 * /registerlandmark [x y z] [name...]
 *
 * Registers a named point with the web service's landmark list (see
 * McBlockReporterPlugin#registerLandmark). Coordinates are optional -- a
 * player registers their own current position when the first three
 * tokens don't look like coordinates at all (including when there simply
 * aren't three of them). Each of x/y/z also accepts vanilla Minecraft's
 * "~" relative syntax -- bare "~" for "my current coordinate on this
 * axis", or "~<offset>" (e.g. "~5", "~-3") for an offset from it -- only
 * meaningful for a player sender, since console has no position to be
 * relative to. Everything after the coordinates (or *everything*, if none
 * were supplied) is rejoined with spaces as the name -- no quoting needed
 * for a multi-word one, unlike treating a single following token as the
 * whole label.
 */
public class RegisterLandmarkCommand implements CommandExecutor {

    private final McBlockReporterPlugin plugin;

    public RegisterLandmarkCommand(McBlockReporterPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean isCoordToken(String token) {
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            return rest.isEmpty() || rest.matches("-?\\d+");
        }
        return token.matches("-?\\d+");
    }

    private static int resolveAxis(String token, int playerAxisValue) {
        if (token.startsWith("~")) {
            String rest = token.substring(1);
            return rest.isEmpty() ? playerAxisValue : playerAxisValue + Integer.parseInt(rest);
        }
        return Integer.parseInt(token);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean hasCoords = args.length >= 3
                && isCoordToken(args[0]) && isCoordToken(args[1]) && isCoordToken(args[2]);

        Location location;
        String landmarkLabel;

        if (hasCoords) {
            boolean usesRelative = args[0].startsWith("~") || args[1].startsWith("~") || args[2].startsWith("~");
            if (usesRelative && !(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console can't use '~' -- there's no position for it to be relative to. Specify absolute coordinates instead.");
                return true;
            }

            World world = (sender instanceof Player)
                    ? ((Player) sender).getWorld()
                    : plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "No world available to resolve coordinates.");
                return true;
            }

            try {
                Location playerLoc = (sender instanceof Player) ? ((Player) sender).getLocation() : null;
                int x = resolveAxis(args[0], playerLoc == null ? 0 : playerLoc.getBlockX());
                int y = resolveAxis(args[1], playerLoc == null ? 0 : playerLoc.getBlockY());
                int z = resolveAxis(args[2], playerLoc == null ? 0 : playerLoc.getBlockZ());
                location = new Location(world, x, y, z);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid coordinates. Usage: /" + label + " [x y z] [name...]");
                return true;
            }
            landmarkLabel = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify coordinates. Usage: /" + label + " <x> <y> <z> [name...]");
                return true;
            }
            location = ((Player) sender).getLocation();
            landmarkLabel = String.join(" ", args).trim();
        }

        if (landmarkLabel.isEmpty()) {
            landmarkLabel = null;
        }

        sender.sendMessage(ChatColor.YELLOW + "Registering landmark at "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "...");
        plugin.registerLandmark(location, landmarkLabel, sender);
        return true;
    }
}
