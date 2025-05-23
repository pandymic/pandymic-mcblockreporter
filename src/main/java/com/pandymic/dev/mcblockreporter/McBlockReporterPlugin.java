package com.pandymic.dev.mcblockreporter;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.Instrument;
import org.bukkit.Note;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.Set;
import java.util.stream.Collectors;

public class McBlockReporterPlugin extends JavaPlugin {

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String apiUrl;
    private String commandReportUrl;
    private String commandReportMethod; // Method for the /httpblockinfo command

    private String monitorBatchUrl;
    private String monitorBatchMethod;

    private String monitorUpdateUrl;
    private String monitorUpdateMethod;
    
    // Changed from Set<Location> to Map<Location, Integer> to store index
    private final Map<Location, Integer> monitoredBlockIndexMap = new HashMap<>();
    private final Set<Location> updateCooldownLocations = new HashSet<>();

    @Override
    public void onEnable() {
        getLogger().info("McBlockReporterPlugin has been enabled!");
        saveDefaultConfig();
        apiUrl = getConfig().getString("apiUrl", "UNCONFIGURED_BASE_API_URL");
        
        if ("UNCONFIGURED_BASE_API_URL".equals(apiUrl) || apiUrl.isEmpty()) {
            getLogger().severe("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            getLogger().severe("Plugin 'apiUrl' is not configured in config.yml! Plugin may not function correctly.");
            getLogger().severe("Please set 'apiUrl' to your web service's base URL.");
            getLogger().severe("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        }

        String reportEndpointPath = getConfig().getString("reportEndpoint", "/report"); // Default from your description
        commandReportUrl = apiUrl + reportEndpointPath;
        commandReportMethod = "POST"; // Typically POST for sending new data, can be made configurable if needed

        // Monitored Blocks configuration
        String monitorDefaultMethod = getConfig().getString("monitoredBlocks.method", "PUT").toUpperCase();
        String monitorDefaultEndpointPath = getConfig().getString("monitoredBlocks.endpoint", "/monitor");

        monitorUpdateMethod = getConfig().getString("monitoredBlocks.update.method", monitorDefaultMethod).toUpperCase();
        String monitorUpdateEndpointPath = getConfig().getString("monitoredBlocks.update.endpoint", monitorDefaultEndpointPath);
        monitorUpdateUrl = apiUrl + monitorUpdateEndpointPath;

        monitorBatchMethod = getConfig().getString("monitoredBlocks.batch.method", monitorDefaultMethod).toUpperCase();
        String monitorBatchEndpointPath = getConfig().getString("monitoredBlocks.batch.endpoint", monitorDefaultEndpointPath);
        monitorBatchUrl = apiUrl + monitorBatchEndpointPath;

        PluginCommand httpBlockInfoCmd = getCommand("httpblockinfo");
        if (httpBlockInfoCmd != null) {
            httpBlockInfoCmd.setExecutor(new HttpBlockInfoCommand(this));
        } else {
            getLogger().log(Level.SEVERE, "Command 'httpblockinfo' not found in plugin.yml!");
        }
        PluginCommand localBlockInfoCmd = getCommand("localblockinfo");
        if (localBlockInfoCmd != null) {
            localBlockInfoCmd.setExecutor(new HttpBlockInfoCommand(this));
        } else {
            getLogger().log(Level.SEVERE, "Command 'localblockinfo' not found in plugin.yml! Please ensure it is registered.");
        }
        getLogger().log(Level.INFO, "Base API URL: " + apiUrl);
        getLogger().log(Level.INFO, "Command Report URL: " + commandReportUrl + " (Method: " + commandReportMethod + ")");
        getLogger().log(Level.INFO, "Monitor Batch URL: " + monitorBatchUrl + " (Method: " + monitorBatchMethod + ")");
        getLogger().log(Level.INFO, "Monitor Update URL: " + monitorUpdateUrl + " (Method: " + monitorUpdateMethod + ")");

        loadMonitoredLocations();
        sendInitialMonitoredData();
        getServer().getPluginManager().registerEvents(new BlockMonitorListener(this), this);
    }

    @Override
    public void onDisable() {
        getLogger().info("McBlockReporterPlugin has been disabled!");
    }

    private void loadMonitoredLocations() {
        monitoredBlockIndexMap.clear();
        java.util.List<Map<?, ?>> locationsFromConfig = getConfig().getMapList("monitoredBlocks.locations");
        if (locationsFromConfig == null || locationsFromConfig.isEmpty()) {
            getLogger().info("No locations configured for monitoring.");
            return;
        }

        int currentIndex = 0; // Start indexing from 0
        for (Map<?, ?> locMap : locationsFromConfig) {
            try {
                // Ensure all keys exist before trying to access them
                String worldName = (String) locMap.get("world");
                int x = ((Number) locMap.get("x")).intValue();
                int y = ((Number) locMap.get("y")).intValue();
                int z = ((Number) locMap.get("z")).intValue();

                org.bukkit.World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    getLogger().warning("World '" + worldName + "' not found for monitored location. Skipping.");
                    continue;
                }
                monitoredBlockIndexMap.put(new Location(world, x, y, z), currentIndex++);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error parsing a monitored location from config: " + locMap.toString(), e);
            }
        }
        getLogger().info("Loaded " + monitoredBlockIndexMap.size() + " locations for monitoring.");
    }


    public Map<String, Object> buildBlockDataMap(Location location, Object extraData) {
        Block block = location.getBlock();
        BlockState state = block.getState();
        BlockData data = block.getBlockData();
        Map<String, Object> blockData = new HashMap<>();
        blockData.put("world", block.getWorld().getName());
        blockData.put("x", block.getX());
        blockData.put("y", block.getY());
        blockData.put("z", block.getZ());
        blockData.put("material", block.getType().toString());

        // Add the numerical index if this block is a monitored one
        if (monitoredBlockIndexMap.containsKey(location)) {
            blockData.put("id", monitoredBlockIndexMap.get(location));
        }

        // Add block data based on the BlockData
        if (data instanceof Powerable) {
            blockData.put("powered", ((Powerable) data).isPowered());
        }
        if (data instanceof Directional) {
            blockData.put("facing", ((Directional) data).getFacing().toString());
        }
        if (data instanceof Rotatable) {
            blockData.put("rotation", ((Rotatable) data).getRotation().toString());
        }
        if (data instanceof Openable) {
            blockData.put("open", ((Openable) data).isOpen());
        }
        if (data instanceof Waterlogged) {
            blockData.put("waterlogged", ((Waterlogged) data).isWaterlogged());
        }
        if (data instanceof Lightable) {
            blockData.put("lit", ((Lightable) data).isLit());
        }
        if (data instanceof Ageable) {
            blockData.put("age", ((Ageable) data).getAge());
            blockData.put("maximumAge", ((Ageable) data).getMaximumAge());
        }
        if (data instanceof Levelled) {
            blockData.put("level", ((Levelled) data).getLevel());
            blockData.put("maximumLevel", ((Levelled) data).getMaximumLevel());
        }
        if (data instanceof Bisected) {
            blockData.put("half", ((Bisected) data).getHalf().toString());
        }
        if (data instanceof Slab) {
            blockData.put("type", ((Slab) data).getType().toString());
        }
        if (data instanceof Stairs) {
            blockData.put("shape", ((Stairs) data).getShape().toString());
            blockData.put("facing", ((Stairs) data).getFacing().toString());
            blockData.put("half", ((Stairs) data).getHalf().toString());
        }
        if (data instanceof FaceAttachable) {
            blockData.put("face", ((FaceAttachable) data).getAttachedFace().toString());
        }
        if (data instanceof AnaloguePowerable) {
            blockData.put("power", ((AnaloguePowerable) data).getPower());
            blockData.put("maximum_power", ((AnaloguePowerable) data).getMaximumPower());
        }
        if (data instanceof MultipleFacing) {
            blockData.put("mf_faces", ((MultipleFacing) data).getFaces().stream().map(BlockFace::toString).collect(Collectors.toList()));
        }
        if (data instanceof Orientable) {
            blockData.put("axis", ((Orientable) data).getAxis().toString());
        }
        if (data instanceof Snowable) {
            blockData.put("snowy", ((Snowable) data).isSnowy());
        }

        // Specific Block Types
        if (data instanceof Bed) {
            blockData.put("part", ((Bed) data).getPart().toString());
            blockData.put("occupied", ((Bed) data).isOccupied());
        }
        if (data instanceof Bell) {
            blockData.put("attachment", ((Bell) data).getAttachment().toString());
        }
        if (data instanceof Campfire) {
            blockData.put("signal_fire", ((Campfire) data).isSignalFire());
        }
        if (data instanceof Candle) {
            blockData.put("candle_count", ((Candle) data).getCandles());
            // Lit and Waterlogged are covered by Lightable and Waterlogged interfaces
        }
        if (data instanceof Cake) {
            blockData.put("bites", ((Cake) data).getBites());
        }
        if (data instanceof Chest) {
            blockData.put("chest_type", ((Chest) data).getType().toString());
            // Waterlogged and Directional (facing) are covered by interfaces
        }
        if (data instanceof ChiseledBookshelf) {
            for (int i = 0; i <= 5; i++) {
                blockData.put("slot_" + i + "_occupied", ((ChiseledBookshelf) data).isSlotOccupied(i));
            }
            // Facing is covered by Directional
        }
        if (data instanceof CommandBlock) {
            blockData.put("conditional", ((CommandBlock) data).isConditional());
            // Facing is covered by Directional
        }
        if (data instanceof Comparator) {
            blockData.put("mode", ((Comparator) data).getMode().toString());
            // Facing, Powerable are covered by interfaces
        }
        if (data instanceof Dispenser) { // Also applies to Dropper as Dispenser extends Dropper
            blockData.put("triggered", ((Dispenser) data).isTriggered());
            // Facing, Powerable are covered by interfaces
        }
        if (data instanceof EndPortalFrame) {
            blockData.put("eye", ((EndPortalFrame) data).hasEye());
            // Facing is covered by Directional
        }
        if (data instanceof Farmland) {
            blockData.put("moisture", ((Farmland) data).getMoisture());
        }
        if (data instanceof Gate) {
            blockData.put("in_wall", ((Gate) data).isInWall());
            // Facing, Openable, Powerable are covered by interfaces
        }
        if (data instanceof Hopper) {
            blockData.put("enabled", ((Hopper) data).isEnabled());
            // Facing is covered by Directional
        }
        if (data instanceof Jigsaw) {
            blockData.put("orientation", ((Jigsaw) data).getOrientation().toString());
        }
        if (data instanceof Jukebox) {
            blockData.put("has_record", ((Jukebox) data).hasRecord());
        }
        if (data instanceof Lantern) {
            blockData.put("hanging", ((Lantern) data).isHanging());
            // Waterlogged is covered by interface
        }
        if (data instanceof Lectern) {
            blockData.put("has_book", ((Lectern) data).hasBook());
            // Facing, Powerable are covered by interfaces
        }
        if (data instanceof NoteBlock) {
            blockData.put("instrument", ((NoteBlock) data).getInstrument().toString());
            blockData.put("note", ((NoteBlock) data).getNote().getId()); // Note is an object, get its ID
            // Powerable is covered by interface
        }
        if (data instanceof Piston) {
            blockData.put("extended", ((Piston) data).isExtended());
            // Facing is covered by Directional
        }
        if (data instanceof TechnicalPiston) { // For Piston base block type
            blockData.put("piston_type", ((TechnicalPiston) data).getType().toString());
        }
        if (data instanceof BrewingStand) {
            for (int i = 0; i <= 2; i++) {
                blockData.put("has_bottle_" + i, ((BrewingStand) data).hasBottle(i));
            }
        }

        // Add more BlockState/BlockData checks as needed for other properties

        if (extraData != null) {
            blockData.put("extraData", extraData);
        }

        if (data instanceof Rail) {
            blockData.put("rail_shape", ((Rail) data).getShape().toString());
        }
        if (data instanceof RedstoneWire) {
            blockData.put("north_wire_connection", ((RedstoneWire) data).getFace(BlockFace.NORTH).toString());
            blockData.put("east_wire_connection", ((RedstoneWire) data).getFace(BlockFace.EAST).toString());
            blockData.put("south_wire_connection", ((RedstoneWire) data).getFace(BlockFace.SOUTH).toString());
            blockData.put("west_wire_connection", ((RedstoneWire) data).getFace(BlockFace.WEST).toString());
            // Power is covered by AnaloguePowerable
        }
        if (data instanceof Repeater) {
            blockData.put("delay", ((Repeater) data).getDelay());
            blockData.put("locked", ((Repeater) data).isLocked());
            // Facing, Powerable are covered by interfaces
        }
        if (data instanceof Sapling) {
            blockData.put("stage", ((Sapling) data).getStage());
            blockData.put("maximum_stage", ((Sapling) data).getMaximumStage());
        }
        if (data instanceof Scaffolding) {
            blockData.put("bottom", ((Scaffolding) data).isBottom());
            blockData.put("distance", ((Scaffolding) data).getDistance());
            // Waterlogged is covered by interface
        }
        if (data instanceof SeaPickle) {
            blockData.put("pickles", ((SeaPickle) data).getPickles());
            // Waterlogged is covered by interface
        }
        if (data instanceof StructureBlock) {
            blockData.put("structure_mode", ((StructureBlock) data).getMode().toString());
        }
        if (data instanceof TNT) {
            blockData.put("unstable", ((TNT) data).isUnstable());
        }
        if (data instanceof Tripwire) {
            blockData.put("attached", ((Tripwire) data).isAttached());
            blockData.put("disarmed", ((Tripwire) data).isDisarmed());
            // Powerable, MultipleFacing are covered by interfaces
        }
        if (data instanceof TurtleEgg) {
            blockData.put("eggs", ((TurtleEgg) data).getEggs());
            blockData.put("hatch_stage", ((TurtleEgg) data).getHatch());
        }
        if (data instanceof Wall) {
            blockData.put("up", ((Wall) data).isUp());
            blockData.put("north_wall_height", ((Wall) data).getHeight(BlockFace.NORTH).toString());
            blockData.put("east_wall_height", ((Wall) data).getHeight(BlockFace.EAST).toString());
            blockData.put("south_wall_height", ((Wall) data).getHeight(BlockFace.SOUTH).toString());
            blockData.put("west_wall_height", ((Wall) data).getHeight(BlockFace.WEST).toString());
            // Waterlogged is covered by interface
        }

        return blockData;
    }

    public String buildBlockDataJson(Location location, Object extraData) {
        Map<String, Object> blockDataMap = buildBlockDataMap(location, extraData);
        return gson.toJson(blockDataMap);
    }

    private void sendPayload(String fullUrl, String jsonData, String httpMethod) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json")
                .method(httpMethod.toUpperCase(), HttpRequest.BodyPublishers.ofString(jsonData))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    getLogger().log(Level.INFO, "Payload sent successfully via " + httpMethod + " to " + fullUrl + ". Response: " + response.body());
                } else {
                    getLogger().log(Level.WARNING, "Failed to send payload via " + httpMethod + " to " + fullUrl + ". Status: " + response.statusCode() + ", Response: " + response.body());
                }
            })
            .exceptionally(e -> {
                getLogger().log(Level.SEVERE, "Error sending payload via " + httpMethod + " to " + fullUrl + ": " + e.getMessage(), e);
                return null;
            });
    }

    public void sendBlockData(Location location, Object extraData) {
        String jsonData = buildBlockDataJson(location, extraData); // This already includes extraData in its map if not null
        sendPayload(this.commandReportUrl, jsonData, this.commandReportMethod);
    }

    private void sendInitialMonitoredData() {
        if (monitoredBlockIndexMap.isEmpty()) {
            return;
        }
        getLogger().info("Sending initial data for " + monitoredBlockIndexMap.size() + " monitored blocks to " + monitorBatchUrl + " via " + monitorBatchMethod + "...");
        ArrayList<Map<String, Object>> batchData = new ArrayList<>();
        for (Location loc : monitoredBlockIndexMap.keySet()) {
            // Ensure the world and chunk are loaded before getting block data
            if (!loc.isWorldLoaded() || !loc.getChunk().isLoaded()) {
                getLogger().warning("Skipping initial data for unloaded location: " + loc.toString());
                continue;
            }
            batchData.add(buildBlockDataMap(loc, null)); // extraData is null for automated sends
        }

        if (batchData.isEmpty()) {
            getLogger().info("No loaded blocks to send in initial batch.");
            return;
        }

        String jsonBatchData = gson.toJson(batchData);
        sendPayload(this.monitorBatchUrl, jsonBatchData, this.monitorBatchMethod);
    }

    public void handleMonitoredBlockUpdate(Block block) {
        Location blockLocation = block.getLocation();
        // Normalize location if needed, though direct comparison should work if Location objects are created consistently
        if (monitoredBlockIndexMap.containsKey(blockLocation)) {
            getLogger().info("Monitored block changed at " + blockLocation.toString() + ". Sending update...");
            Map<String, Object> blockMap = buildBlockDataMap(blockLocation, null); // extraData is null
            String jsonData = gson.toJson(blockMap);
            sendPayload(this.monitorUpdateUrl, jsonData, this.monitorUpdateMethod);
        }
    }

    // Overloaded method for when no extraData is provided
    public void sendBlockData(Location location) {
        sendBlockData(location, null);
    }

    // Helper for the listener to check if a block is monitored
    public boolean isBlockMonitored(Location location) {
        return monitoredBlockIndexMap.containsKey(location);
    }

    public boolean isLocationOnUpdateCooldown(Location location) {
        return updateCooldownLocations.contains(location);
    }

    public void addLocationToUpdateCooldown(Location location) {
        updateCooldownLocations.add(location);
    }

    public void removeLocationFromUpdateCooldown(Location location) {
        updateCooldownLocations.remove(location);
    }
}
