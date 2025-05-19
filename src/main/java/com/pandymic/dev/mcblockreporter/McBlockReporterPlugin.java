package com.pandymic.dev.mcblockreporter;

import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class McBlockReporterPlugin extends JavaPlugin {

    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String apiUrl;

    @Override
    public void onEnable() {
        getLogger().info("McBlockReporterPlugin has been enabled!");
        saveDefaultConfig();
        apiUrl = getConfig().getString("api-endpoint", "YOUR_DEFAULT_API_ENDPOINT");
        getCommand("httpblockinfo").setExecutor(new HttpBlockInfoCommand(this));
        getLogger().log(Level.INFO, "API Endpoint loaded: " + apiUrl);
    }

    @Override
    public void onDisable() {
        getLogger().info("McBlockReporterPlugin has been disabled!");
    }

    public void sendBlockData(Location location, Object extraData) {
        Block block = location.getBlock();
        BlockState state = block.getState();
        BlockData data = block.getBlockData();
        Map<String, Object> blockData = new HashMap<>();
        blockData.put("world", block.getWorld().getName());
        blockData.put("x", block.getX());
        blockData.put("y", block.getY());
        blockData.put("z", block.getZ());
        blockData.put("material", block.getType().toString());

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

        // Add more BlockState/BlockData checks as needed for other properties

        if (extraData != null) {
            blockData.put("extraData", extraData);
        }

        String jsonData = gson.toJson(blockData);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonData))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        getLogger().log(Level.INFO, "Block data sent successfully. Response: " + response.body());
                    } else {
                        getLogger().log(Level.WARNING, "Failed to send block data. Status code: " + response.statusCode() + ", Response: " + response.body());
                    }
                })
                .exceptionally(e -> {
                    getLogger().log(Level.SEVERE, "Error sending block data: " + e.getMessage(), e);
                    return null;
                });
    }

    // Overloaded method for when no extraData is provided
    public void sendBlockData(Location location) {
        sendBlockData(location, null);
    }
}
