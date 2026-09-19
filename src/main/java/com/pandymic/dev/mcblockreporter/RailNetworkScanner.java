package com.pandymic.dev.mcblockreporter;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;
import org.bukkit.block.sign.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Traces a connected rail network from a starting point via BFS. Fully
 * read-only -- never places, breaks, or changes any block.
 *
 * Each rail's current Rail.Shape (already resolved by the game engine, e.g.
 * in response to nearby redstone/lever state) gives its two "active"
 * connections. Separately, every geometrically adjacent position (4
 * cardinal directions, each at the same Y, one up, and one down, to cover
 * slopes) is checked for a rail block regardless of the current shape --
 * more than two such neighbors marks an "intersection", since that means
 * more track exists there than a simple pass-through uses. An intersection
 * with a lever nearby (same level, or one block up, in any of the 4
 * cardinal directions) is further marked a "junction": a switch whose
 * active branch is controlled by that lever. A sign nearby any node (not
 * just junctions -- an ordinary stop along straight track is the common
 * case) marks it a "station," named after the sign's first line.
 *
 * At each junction, this records which of the two possible shapes is
 * associated with the lever's *current* powered state -- e.g. if the lever
 * reads powered right now and the switch-rail's current shape is
 * SOUTH_EAST, that pairing goes in as poweredShape, with unpoweredShape
 * left null (unknown). Route planning (pandymic-mcservice's
 * /rail-networks/:id/route) needs *both* pairings to work out which lever
 * state a given connection needs -- the *other* one is filled in
 * separately, opportunistically, by the service watching real block
 * reports over time (see pandymic-mcservice's actions.js monitor handler)
 * or by a fresh re-scan after someone flips the lever by hand.
 *
 * This scanner does **not** attempt to toggle a lever itself to observe
 * both states directly, despite that seeming like the obvious approach --
 * confirmed live, several independent ways (direct BlockData mutation, a
 * dispatched `setblock` command, the same plus forcing a re-notify on the
 * lever's support block, all with and without an added settle delay, and
 * even bare-replacing the switch-rail immediately after toggling), that a
 * command-driven lever state change does not reliably cause the connected
 * rail to recompute its shape on this server -- even though the lever's
 * own reported state updates correctly every time. A genuine in-game
 * interaction (a real right-click) does trigger it correctly; nothing
 * remote-controlled that was tried does. Rather than keep guessing at
 * that, this only ever records what's genuinely, currently true.
 */
public class RailNetworkScanner {

    private static final int SEARCH_RADIUS = 3;
    // Fallback only, used if the caller doesn't supply one (see scan()
    // below) -- the real cap is config-driven (railNetworks.maxNodes in
    // config.yml, read by ScanRailNetworkCommand) specifically so raising
    // it later is a restart, not a rebuild. Originally a hardcoded 5000;
    // raised once already after a real network ("choo_choo", a
    // map-spanning powered-rail line) hit that cap with the vast majority
    // of its track still unmapped -- prompting this to become configurable
    // rather than requiring another rebuild next time. Still a hard stop,
    // not a soft target: with very few junctions expected on a line like
    // that (confirmed: zero found in the first 5000 nodes), growth here is
    // bounded by actual track length, not combinatorial branching, so
    // raising it is safe in the sense that it can't runaway-explode -- it
    // can only ever do proportionally more of the same fast, read-only
    // work.
    private static final int DEFAULT_MAX_NODES = 50000;

    private static final Set<Material> RAIL_MATERIALS = new HashSet<>(Arrays.asList(
            Material.RAIL, Material.POWERED_RAIL, Material.DETECTOR_RAIL, Material.ACTIVATOR_RAIL
    ));

    // Plain x/y/z, not a Bukkit Location -- Location holds a reference to its
    // World, which Gson can't serialize (circular/huge internal state), the
    // same reason buildBlockDataMap never serializes Locations directly.
    public static class Pos {
        public final int x, y, z;
        Pos(Block block) { this.x = block.getX(); this.y = block.getY(); this.z = block.getZ(); }
    }

    public static class Station {
        public final String name;
        public final int x, y, z;
        Station(String name, Block signBlock) {
            this.name = name;
            this.x = signBlock.getX();
            this.y = signBlock.getY();
            this.z = signBlock.getZ();
        }
    }

    public static class Node {
        public final int x, y, z;
        public final String material;
        public final String shape;
        public boolean isIntersection = false;
        public boolean isJunction = false;
        public Pos lever = null;
        // Only set when isJunction -- whichever of these matches the
        // lever's *current* powered state gets this node's own `shape`;
        // the other starts null (see class Javadoc -- filled in later,
        // opportunistically, not by this scan). Route planning treats a
        // still-null one as "unknown, can't route through this junction
        // that way yet" rather than assuming.
        public String poweredShape = null;
        public String unpoweredShape = null;
        public Station station = null;

        Node(Block block, Rail.Shape shape) {
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
            this.material = block.getType().toString();
            this.shape = shape.toString();
        }
    }

    public static class Edge {
        public final Pos from;
        public final Pos to;

        Edge(Block from, Block to) {
            this.from = new Pos(from);
            this.to = new Pos(to);
        }
    }

    public static class Result {
        public String error;
        public final List<Node> nodes = new ArrayList<>();
        public final List<Edge> edges = new ArrayList<>();
        public boolean truncated = false;
    }

    public static Result scan(World world, int x, Integer y, int z) {
        return scan(world, x, y, z, DEFAULT_MAX_NODES);
    }

    public static Result scan(World world, int x, Integer y, int z, int maxNodes) {
        Result result = new Result();
        Block start = findStartingRail(world, x, y, z);
        if (null == start) {
            result.error = "No rail found within " + SEARCH_RADIUS + " blocks of "
                    + x + "," + (null != y ? y : "<any y>") + "," + z;
            return result;
        }
        traverse(start, result, maxNodes);
        return result;
    }

    private static Block findStartingRail(World world, int x, Integer y, int z) {
        if (null != y) {
            return findNearestRailAtY(world, x, y, z);
        }
        // No Y given: search outward from the surface (surface, +1, -1, +2,
        // -2, ...) rather than scanning bottom-up, so a nearby surface rail
        // is found before an unrelated rail deep in a mine.
        int surfaceY = world.getHighestBlockYAt(x, z);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        for (int offset = 0; surfaceY - offset >= minY || surfaceY + offset <= maxY; offset++) {
            if (surfaceY + offset <= maxY) {
                Block found = findNearestRailAtY(world, x, surfaceY + offset, z);
                if (null != found) return found;
            }
            if (offset > 0 && surfaceY - offset >= minY) {
                Block found = findNearestRailAtY(world, x, surfaceY - offset, z);
                if (null != found) return found;
            }
        }
        return null;
    }

    private static Block findNearestRailAtY(World world, int x, int y, int z) {
        Block nearest = null;
        int nearestDistSq = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                Block block = world.getBlockAt(x + dx, y, z + dz);
                if (RAIL_MATERIALS.contains(block.getType())) {
                    int distSq = dx * dx + dz * dz;
                    if (distSq < nearestDistSq) {
                        nearestDistSq = distSq;
                        nearest = block;
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * The two relative offsets {dx, dy, dz} a given resolved shape connects
     * to. North = -Z, South = +Z, East = +X, West = -X (standard Minecraft
     * convention). An ASCENDING_<dir> rail steps up by one block in <dir>
     * and stays level in the opposite direction -- e.g. ASCENDING_NORTH
     * connects to (0,+1,-1) [north, one higher] and (0,0,+1) [south, level].
     */
    private static int[][] shapeOffsets(Rail.Shape shape) {
        switch (shape) {
            case NORTH_SOUTH: return new int[][]{{0, 0, -1}, {0, 0, 1}};
            case EAST_WEST: return new int[][]{{1, 0, 0}, {-1, 0, 0}};
            case ASCENDING_NORTH: return new int[][]{{0, 1, -1}, {0, 0, 1}};
            case ASCENDING_SOUTH: return new int[][]{{0, 0, -1}, {0, 1, 1}};
            case ASCENDING_EAST: return new int[][]{{1, 1, 0}, {-1, 0, 0}};
            case ASCENDING_WEST: return new int[][]{{1, 0, 0}, {-1, 1, 0}};
            case SOUTH_EAST: return new int[][]{{0, 0, 1}, {1, 0, 0}};
            case SOUTH_WEST: return new int[][]{{0, 0, 1}, {-1, 0, 0}};
            case NORTH_WEST: return new int[][]{{0, 0, -1}, {-1, 0, 0}};
            case NORTH_EAST: return new int[][]{{0, 0, -1}, {1, 0, 0}};
            default: return new int[][]{};
        }
    }

    // Every position worth checking for "is there a rail here" regardless
    // of the current shape: 4 cardinal directions, each at the same Y, one
    // up, and one down (covers a slope meeting this block from either side).
    private static final int[][] CANDIDATE_NEIGHBOR_OFFSETS = {
            {0, 0, -1}, {0, 1, -1}, {0, -1, -1},
            {0, 0, 1}, {0, 1, 1}, {0, -1, 1},
            {1, 0, 0}, {1, 1, 0}, {1, -1, 0},
            {-1, 0, 0}, {-1, 1, 0}, {-1, -1, 0},
    };

    // Positions checked for a controlling lever near an intersection, or a
    // station sign near any node: this block and one above, in the 4
    // cardinal directions (including 0,0 -- directly on top of the block).
    // Shared between both searches -- geometrically the same "immediately
    // adjacent" pattern either way.
    private static final int[][] ADJACENT_SEARCH_OFFSETS = {
            {0, 0, 0}, {0, 1, 0},
            {1, 0, 0}, {1, 1, 0}, {-1, 0, 0}, {-1, 1, 0},
            {0, 0, 1}, {0, 1, 1}, {0, 0, -1}, {0, 1, -1},
    };

    private static void traverse(Block start, Result result, int maxNodes) {
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(posKey(start));

        while (!queue.isEmpty()) {
            if (result.nodes.size() >= maxNodes) {
                result.truncated = true;
                break;
            }
            Block block = queue.poll();
            BlockData data = block.getBlockData();
            if (!(data instanceof Rail)) {
                continue;
            }
            Rail rail = (Rail) data;
            Node node = new Node(block, rail.getShape());

            Sign sign = findNearbySign(block);
            if (null != sign) {
                String name = sign.getSide(Side.FRONT).getLine(0);
                if (null != name && !name.trim().isEmpty()) {
                    node.station = new Station(name.trim(), sign.getBlock());
                }
            }

            List<Block> railNeighbors = new ArrayList<>();
            for (int[] off : CANDIDATE_NEIGHBOR_OFFSETS) {
                Block neighbor = block.getRelative(off[0], off[1], off[2]);
                if (RAIL_MATERIALS.contains(neighbor.getType())) {
                    railNeighbors.add(neighbor);
                }
            }

            if (railNeighbors.size() > 2) {
                node.isIntersection = true;
                Block lever = findNearbyLever(block);
                if (null != lever) {
                    node.isJunction = true;
                    node.lever = new Pos(lever);
                    BlockData leverData = lever.getBlockData();
                    if (leverData instanceof Powerable) {
                        // Whichever of these two the lever's live state
                        // currently is, that pairing is genuinely known --
                        // see class Javadoc for why this scanner doesn't
                        // try to toggle it itself to also learn the other.
                        if (((Powerable) leverData).isPowered()) {
                            node.poweredShape = node.shape;
                        } else {
                            node.unpoweredShape = node.shape;
                        }
                    }
                }
            }

            for (Block neighbor : railNeighbors) {
                String edgeKey = canonicalEdgeKey(block, neighbor);
                if (edgeKeys.add(edgeKey)) {
                    result.edges.add(new Edge(block, neighbor));
                }
                String neighborKey = posKey(neighbor);
                if (!visited.contains(neighborKey)) {
                    visited.add(neighborKey);
                    queue.add(neighbor);
                }
            }

            result.nodes.add(node);
        }
    }

    private static Block findNearbyLever(Block block) {
        for (int[] off : ADJACENT_SEARCH_OFFSETS) {
            Block candidate = block.getRelative(off[0], off[1], off[2]);
            if (Material.LEVER == candidate.getType()) {
                return candidate;
            }
        }
        return null;
    }

    private static Sign findNearbySign(Block block) {
        for (int[] off : ADJACENT_SEARCH_OFFSETS) {
            Block candidate = block.getRelative(off[0], off[1], off[2]);
            if (candidate.getState() instanceof Sign) {
                return (Sign) candidate.getState();
            }
        }
        return null;
    }

    private static String posKey(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static String canonicalEdgeKey(Block a, Block b) {
        String keyA = posKey(a);
        String keyB = posKey(b);
        return keyA.compareTo(keyB) < 0 ? keyA + "|" + keyB : keyB + "|" + keyA;
    }
}
