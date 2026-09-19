package com.pandymic.dev.mcblockreporter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Axis;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.Instrument;
import org.bukkit.Note;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    private String blocksListUrl;
    private String blocksRegisterUrl;

    private String railNetworksIngestUrl;

    private String landmarksRegisterUrl;

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

        // Registry endpoints: which blocks are monitored is owned by the web
        // service, not this config file. See refreshMonitoredLocationsFromService().
        String blocksListEndpointPath = getConfig().getString("monitoredBlocks.listEndpoint", "/blocks");
        blocksListUrl = apiUrl + blocksListEndpointPath;
        String blocksRegisterEndpointPath = getConfig().getString("monitoredBlocks.registerEndpoint", "/blocks");
        blocksRegisterUrl = apiUrl + blocksRegisterEndpointPath;

        String railNetworksIngestEndpointPath = getConfig().getString("railNetworks.ingestEndpoint", "/rail-networks/ingest");
        railNetworksIngestUrl = apiUrl + railNetworksIngestEndpointPath;

        String landmarksRegisterEndpointPath = getConfig().getString("landmarks.registerEndpoint", "/landmarks");
        landmarksRegisterUrl = apiUrl + landmarksRegisterEndpointPath;

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
        PluginCommand registerBlockMonitorCmd = getCommand("registerblockmonitor");
        if (registerBlockMonitorCmd != null) {
            registerBlockMonitorCmd.setExecutor(new RegisterBlockMonitorCommand(this));
        } else {
            getLogger().log(Level.SEVERE, "Command 'registerblockmonitor' not found in plugin.yml! Please ensure it is registered.");
        }
        PluginCommand scanRailNetworkCmd = getCommand("scanrailnetwork");
        if (scanRailNetworkCmd != null) {
            scanRailNetworkCmd.setExecutor(new ScanRailNetworkCommand(this));
        } else {
            getLogger().log(Level.SEVERE, "Command 'scanrailnetwork' not found in plugin.yml! Please ensure it is registered.");
        }
        PluginCommand registerLandmarkCmd = getCommand("registerlandmark");
        if (registerLandmarkCmd != null) {
            registerLandmarkCmd.setExecutor(new RegisterLandmarkCommand(this));
        } else {
            getLogger().log(Level.SEVERE, "Command 'registerlandmark' not found in plugin.yml! Please ensure it is registered.");
        }
        getLogger().log(Level.INFO, "Base API URL: " + apiUrl);
        getLogger().log(Level.INFO, "Command Report URL: " + commandReportUrl + " (Method: " + commandReportMethod + ")");
        getLogger().log(Level.INFO, "Monitor Batch URL: " + monitorBatchUrl + " (Method: " + monitorBatchMethod + ")");
        getLogger().log(Level.INFO, "Monitor Update URL: " + monitorUpdateUrl + " (Method: " + monitorUpdateMethod + ")");
        getLogger().log(Level.INFO, "Monitored Blocks List URL: " + blocksListUrl);
        getLogger().log(Level.INFO, "Monitored Blocks Register URL: " + blocksRegisterUrl);

        long refreshIntervalTicks = getConfig().getLong("monitoredBlocks.refreshIntervalSeconds", 30) * 20L;
        refreshMonitoredLocationsFromService();
        getServer().getScheduler().runTaskTimer(this, this::refreshMonitoredLocationsFromService, refreshIntervalTicks, refreshIntervalTicks);
        getServer().getPluginManager().registerEvents(new BlockMonitorListener(this), this);
    }

    @Override
    public void onDisable() {
        getLogger().info("McBlockReporterPlugin has been disabled!");
    }

    /**
     * Fetches the current monitored-block registry from the web service and
     * rebuilds monitoredBlockIndexMap from it. The HTTP call runs off the
     * main thread; applying the result to Bukkit API state is hopped back
     * onto the main thread. Called once on enable and again on a repeating
     * timer to pick up registry changes made via the Web UI, REST API, or
     * another server instance without needing a restart.
     */
    private void refreshMonitoredLocationsFromService() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(blocksListUrl))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Failed to fetch monitored block registry from " + blocksListUrl + ". Status: " + response.statusCode());
                    return;
                }
                Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> entries = gson.fromJson(response.body(), listType);
                getServer().getScheduler().runTask(this, () -> applyRegistryEntries(entries));
            })
            .exceptionally(e -> {
                getLogger().log(Level.WARNING, "Error fetching monitored block registry from " + blocksListUrl, e);
                return null;
            });
    }

    /**
     * Rebuilds monitoredBlockIndexMap from the service's current registry,
     * then re-pushes a fresh state report for *every* currently-known
     * block, not just newly-added ones -- see 2026-09-19 note below for
     * why this changed from only pushing what's new.
     */
    private void applyRegistryEntries(List<Map<String, Object>> entries) {
        Map<Location, Integer> updated = new HashMap<>();
        if (entries != null) {
            for (Map<String, Object> entryMap : entries) {
                try {
                    String worldName = (String) entryMap.get("world");
                    int x = ((Number) entryMap.get("x")).intValue();
                    int y = ((Number) entryMap.get("y")).intValue();
                    int z = ((Number) entryMap.get("z")).intValue();
                    int id = ((Number) entryMap.get("id")).intValue();

                    org.bukkit.World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        getLogger().warning("World '" + worldName + "' not found for registry entry id " + id + ". Skipping.");
                        continue;
                    }
                    updated.put(new Location(world, x, y, z), id);
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Error parsing a monitored block registry entry: " + entryMap, e);
                }
            }
        }
        monitoredBlockIndexMap.clear();
        monitoredBlockIndexMap.putAll(updated);
        getLogger().info("Refreshed monitored block registry: now tracking " + monitoredBlockIndexMap.size() + " block(s).");

        // 2026-09-19: previously this only re-pushed state for locations
        // that weren't already known (fixing blocks added after onEnable
        // sitting with unknown material/properties until touched in-game --
        // still true, still fixed). Broadened to *every* known block, every
        // refresh, after confirming live that a junction's switch-rail
        // genuinely re-switches in response to a real lever flip (the
        // physical mechanic works) but BlockPhysicsEvent never fires for
        // it, so BlockMonitorListener never reports the change -- the
        // switch-rail's reported rail_shape would otherwise go stale
        // forever after the very first report, breaking both the map's
        // live opacity indicator and route planning's shape-learning
        // (railNetworks.recordObservedJunctionState on the service side
        // depends on exactly this data). Re-pushing everything on this
        // existing timer (monitoredBlocks.refreshIntervalSeconds, default
        // 30s) closes that gap without depending on find the "right" event
        // to listen for -- correctness over minimizing request volume,
        // given this project's real monitored-block counts are small.
        if (!monitoredBlockIndexMap.isEmpty()) {
            sendInitialDataFor(new ArrayList<>(monitoredBlockIndexMap.keySet()));
        }
    }

    /**
     * Registers a block with the web service's registry and, once accepted,
     * starts tracking it locally right away (no need to wait for the next
     * periodic refresh) and pushes its current state so the Web UI shows
     * real data immediately instead of nulls.
     */
    public void registerMonitoredBlock(Location location, String label, CommandSender sender) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("world", location.getWorld().getName());
        requestBody.put("x", location.getBlockX());
        requestBody.put("y", location.getBlockY());
        requestBody.put("z", location.getBlockZ());
        if (label != null) {
            requestBody.put("label", label);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(blocksRegisterUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Failed to register block at " + location + ". Status: " + response.statusCode() + ", Response: " + response.body());
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Failed to register block: " + response.body()));
                    return;
                }
                Type entryType = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> entry = gson.fromJson(response.body(), entryType);
                int id = ((Number) entry.get("id")).intValue();

                getServer().getScheduler().runTask(this, () -> {
                    monitoredBlockIndexMap.put(location, id);
                    sender.sendMessage(ChatColor.GREEN + "Registered block at " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + " as id " + id + ".");
                    handleMonitoredBlockUpdate(location.getBlock());
                });
            })
            .exceptionally(e -> {
                getLogger().log(Level.SEVERE, "Error registering block at " + location, e);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Error registering block: " + e.getMessage()));
                return null;
            });
    }

    /**
     * POSTs a completed RailNetworkScanner.Result to the web service in one
     * shot. The scan itself already ran synchronously on the main thread
     * (see ScanRailNetworkCommand); this call itself is fire-and-forget --
     * the service ingests it, auto-registers any junction levers/switch
     * rails into the block registry, and broadcasts a WebSocket update, all
     * independent of this plugin.
     */
    public void reportRailNetworkScan(String world, int x, Integer y, int z, String label,
                                       RailNetworkScanner.Result result, CommandSender sender) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("world", world);
        Map<String, Object> start = new HashMap<>();
        start.put("x", x);
        start.put("y", y);
        start.put("z", z);
        payload.put("start", start);
        if (label != null) {
            payload.put("label", label);
        }
        payload.put("nodes", result.nodes);
        payload.put("edges", result.edges);
        payload.put("truncated", result.truncated);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(railNetworksIngestUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Failed to report rail network scan. Status: " + response.statusCode() + ", Response: " + response.body());
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Failed to report scan to service: " + response.body()));
                    return;
                }
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.GREEN + "Rail network reported to service."));
            })
            .exceptionally(e -> {
                getLogger().log(Level.SEVERE, "Error reporting rail network scan", e);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Error reporting scan: " + e.getMessage()));
                return null;
            });
    }

    /**
     * Registers a named point with the web service's landmark list. Simpler
     * than registerMonitoredBlock -- a landmark has no block state to track
     * or report going forward, just a fixed position, so this is a
     * fire-and-forget POST with no local bookkeeping equivalent to
     * monitoredBlockIndexMap.
     */
    public void registerLandmark(Location location, String label, CommandSender sender) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("world", location.getWorld().getName());
        requestBody.put("x", location.getBlockX());
        requestBody.put("y", location.getBlockY());
        requestBody.put("z", location.getBlockZ());
        if (label != null) {
            requestBody.put("label", label);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(landmarksRegisterUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    getLogger().warning("Failed to register landmark at " + location + ". Status: " + response.statusCode() + ", Response: " + response.body());
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Failed to register landmark: " + response.body()));
                    return;
                }
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.GREEN + "Landmark registered."));
            })
            .exceptionally(e -> {
                getLogger().log(Level.SEVERE, "Error registering landmark at " + location, e);
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED + "Error registering landmark: " + e.getMessage()));
                return null;
            });
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

    private void sendInitialDataFor(List<Location> locations) {
        getLogger().info("Refreshing state for " + locations.size() + " monitored block(s) to " + monitorBatchUrl + " via " + monitorBatchMethod + "...");
        ArrayList<Map<String, Object>> batchData = new ArrayList<>();
        for (Location loc : locations) {
            // Ensure the world and chunk are loaded before getting block data.
            // A location skipped here (unloaded right now) just waits for the
            // *next* periodic refresh to try again -- no longer a one-shot,
            // so a miss here isn't permanent the way it used to be.
            if (!loc.isWorldLoaded() || !loc.getChunk().isLoaded()) {
                getLogger().warning("Skipping state refresh for unloaded location: " + loc.toString());
                continue;
            }
            batchData.add(buildBlockDataMap(loc, null)); // extraData is null for automated sends
        }

        if (batchData.isEmpty()) {
            getLogger().info("No loaded blocks to refresh in this batch.");
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
