package com.pandymic.dev.mcblockreporter;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
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
 * Also pushes each player's cropped face (see cropFace) once on join --
 * skins essentially never change mid-session, so this rides the same
 * connection as a much rarer, separate message type rather than the
 * position-push cadence. Implements Listener itself (registered in
 * McBlockReporterPlugin#onEnable) rather than a separate listener class,
 * since sending a skin still needs this object's own WebSocket/HttpClient
 * plumbing -- keeping it here avoids threading that back out to a second
 * class for no real separation of concerns.
 *
 * The connection is plugin-initiated and reconnects on its own with a
 * fixed delay if it drops (service restart, network blip). There is no
 * fallback on the service side if this plugin isn't connected -- player
 * markers on the map simply stop moving until it reconnects, the same
 * way any other monitored-block report goes stale if the plugin is down.
 */
public class PlayerPositionBroadcaster implements Listener {

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
            .buildAsync(wsUri, new SocketListener())
            .thenAccept(ws -> {
                webSocket = ws;
                plugin.getLogger().info("Player position stream connected to " + wsUri);
                // Covers two cases with one mechanism: the *service* having
                // restarted (its in-memory skin cache is gone, but nobody
                // re-joined to trigger onPlayerJoin again) and *this plugin*
                // having reloaded/reconnected while players were already
                // online (same reason -- no fresh join event either).
                resendAllSkins();
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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Skin fetch is a real network call (to Mojang's texture CDN) --
        // never do that on the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fetchAndSendSkin(player));
    }

    private void resendAllSkins() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> fetchAndSendSkin(player));
        }
    }

    private void fetchAndSendSkin(Player player) {
        try {
            PlayerProfile profile = player.getPlayerProfile();
            PlayerTextures textures = profile.getTextures();
            URL skinUrl = textures.getSkin();
            if (null == skinUrl) {
                // No skin set yet -- can happen very briefly right at join,
                // before the profile's textures have populated. Nothing to
                // send; the reconnect-triggered resend or a future rejoin
                // will pick it up.
                return;
            }

            BufferedImage skin = ImageIO.read(skinUrl);
            if (null == skin) {
                return;
            }

            BufferedImage face = cropFace(skin);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(face, "png", out);
            String base64 = Base64.getEncoder().encodeToString(out.toByteArray());

            sendSkinMessage(player.getName(), base64);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to fetch/crop skin for " + player.getName(), e);
        }
    }

    // Minecraft's skin texture is a fixed grid regardless of overall
    // format (modern 64x64, or legacy pre-1.8 64x32) -- the base face is
    // always at (8,8)-(16,16), and the semi-transparent "hat" overlay worn
    // on top for the normal in-game look is always at (40,8)-(48,16). Both
    // regions live in the texture's top 32 rows, which every format has,
    // so no separate handling is needed for legacy skins.
    private static BufferedImage cropFace(BufferedImage skin) {
        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = face.createGraphics();
        g.drawImage(skin, 0, 0, 8, 8, 8, 8, 16, 16, null);
        g.drawImage(skin, 0, 0, 8, 8, 40, 8, 48, 16, null);
        g.dispose();
        return face;
    }

    private void sendSkinMessage(String name, String base64Png) {
        WebSocket current = webSocket;
        if (null == current || current.isOutputClosed()) {
            return;
        }
        Map<String, Object> message = new HashMap<>();
        message.put("type", "player-skin");
        message.put("name", name);
        message.put("image", base64Png);
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

    // Named distinctly from org.bukkit.event.Listener (implemented by the
    // outer class itself, for the join-event handler above) to avoid a
    // same-name shadowing conflict between the two.
    private class SocketListener implements WebSocket.Listener {
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
