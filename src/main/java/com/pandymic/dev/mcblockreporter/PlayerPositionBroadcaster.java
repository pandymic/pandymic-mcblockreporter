package com.pandymic.dev.mcblockreporter;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;

/**
 * Pushes every online player's live position to the web service over a
 * persistent WebSocket connection, replacing the service's old approach
 * of polling RCON (`data get entity <name> Pos`) for it. Positions are
 * already in memory on this side (Bukkit's own Location objects), so
 * reading them costs nothing -- unlike a round-trip RCON query per
 * player -- and this can push far more often than RCON polling ever
 * could without adding load anywhere.
 *
 * The connection is plugin-initiated and reconnects on its own with a
 * fixed delay if it drops (service restart, network blip). There is no
 * fallback on the service side if this plugin isn't connected -- player
 * markers on the map simply stop moving until it reconnects, the same
 * way any other monitored-block report goes stale if the plugin is down.
 */
public class PlayerPositionBroadcaster {

    private static final long RECONNECT_DELAY_TICKS = 100L; // 5s

    private final McBlockReporterPlugin plugin;
    private final Gson gson = new Gson();
    private final URI wsUri;
    private final long pushIntervalTicks;
    private volatile WebSocket webSocket;
    private volatile boolean stopped = false;
    private volatile boolean reconnectScheduled = false;

    public PlayerPositionBroadcaster(McBlockReporterPlugin plugin, String wsUrl, long pushIntervalTicks) {
        this.plugin = plugin;
        this.wsUri = URI.create(wsUrl);
        this.pushIntervalTicks = pushIntervalTicks;
    }

    public void start() {
        connect();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pushPositions, pushIntervalTicks, pushIntervalTicks);
    }

    public void stop() {
        stopped = true;
        WebSocket current = webSocket;
        if (current != null) {
            current.abort();
        }
    }

    private void connect() {
        if (stopped) {
            return;
        }
        plugin.getHttpClient().newWebSocketBuilder()
            .buildAsync(wsUri, new Listener())
            .thenAccept(ws -> {
                webSocket = ws;
                plugin.getLogger().info("Player position stream connected to " + wsUri);
            })
            .exceptionally(e -> {
                plugin.getLogger().log(Level.WARNING, "Player position stream failed to connect to " + wsUri + ": " + e.getMessage());
                scheduleReconnect();
                return null;
            });
    }

    private void scheduleReconnect() {
        if (stopped || reconnectScheduled) {
            return;
        }
        reconnectScheduled = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            reconnectScheduled = false;
            connect();
        }, RECONNECT_DELAY_TICKS);
    }

    private void pushPositions() {
        WebSocket current = webSocket;
        if (current == null || current.isOutputClosed()) {
            return;
        }

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", player.getName());
            entry.put("x", player.getLocation().getX());
            entry.put("y", player.getLocation().getY());
            entry.put("z", player.getLocation().getZ());
            entry.put("dimension", dimensionLabel(player.getWorld().getEnvironment()));
            players.add(entry);
        }

        Map<String, Object> message = new HashMap<>();
        message.put("type", "player-positions");
        message.put("players", players);
        current.sendText(gson.toJson(message), true);
    }

    // Environment-based rather than World#getKey() -- a custom-named
    // world (this project's is "pandyra") doesn't reliably key as
    // "minecraft:overworld" the way the vanilla default world does, but
    // its Environment is still NORMAL either way.
    private static String dimensionLabel(World.Environment environment) {
        switch (environment) {
            case NORMAL:
                return "overworld";
            case NETHER:
                return "nether";
            case THE_END:
                return "the_end";
            default:
                return environment.name().toLowerCase();
        }
    }

    private class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            plugin.getLogger().warning("Player position stream closed (status " + statusCode + "): " + reason);
            scheduleReconnect();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            plugin.getLogger().log(Level.WARNING, "Player position stream error", error);
            scheduleReconnect();
        }
    }
}
