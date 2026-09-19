package com.pandymic.dev.mcblockreporter;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.sign.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Traces a connected rail network from a starting point via BFS. Read-only
 * for ordinary track -- never places or breaks a block -- with one
 * deliberate exception: at each junction found, its controlling lever is
 * briefly switched both ways to record which rail_shape each position
 * produces (a junction Node's poweredShape/unpoweredShape), then restored
 * to whatever state it was actually in before the scan touched it. This is
 * the only reliable way to know which lever position connects to which
 * neighbor, short of hand-modeling Minecraft's own rail-junction-
 * resolution rules -- needed for route planning (see pandymic-mcservice's
 * /rail-networks/:id/route).
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
        // Only set when isJunction -- the switch-rail's own Rail.Shape with
        // its controlling lever powered vs. unpowered, captured by briefly
        // toggling it during the scan (see class Javadoc). Route planning
        // uses these to work out which lever state a desired connection
        // needs; either can come back null if the switch-rail wasn't
        // actually a Rail anymore by the time it was re-read (unexpected,
        // but route planning has to treat "unknown" as "can't use this
        // junction" rather than assume).
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
                    captureJunctionShapes(node, lever, block);
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

    // Briefly switches `leverBlock` both ways to record what rail_shape
    // `switchRailBlock` resolves to in each state, then restores whatever
    // state the lever was actually in before this call -- see the class
    // Javadoc for why this is the reliable way to learn the mapping
    // (rather than a one-off side effect, it's the only source of truth
    // route planning has).
    //
    // Dispatches an actual `setblock ...[powered=...]` console command
    // rather than mutating the lever's BlockData directly and calling
    // Block#setBlockData(data, true) -- confirmed live (not assumed) that
    // the direct-mutation approach does *not* reliably trigger the same
    // redstone-driven rail-shape recalculation a real setblock command
    // does: a first attempt at this method used it and, against a real
    // junction on this server, captured the exact same shape for both
    // powered and unpowered. This project's own existing lever-toggle path
    // (pandymic-mcservice's PUT /blocks/:id/state) already only ever used
    // setblock over RCON, never direct BlockData mutation -- dispatching
    // the equivalent command locally (Bukkit.dispatchCommand, since RCON
    // commands are themselves just dispatched to this same command system)
    // matches that proven-reliable path instead of a second, untested one.
    private static void captureJunctionShapes(Node node, Block leverBlock, Block switchRailBlock) {
        BlockData leverData = leverBlock.getBlockData();
        if (!(leverData instanceof Switch)) {
            return;
        }
        Switch lever = (Switch) leverData;
        boolean originalPowered = lever.isPowered();
        String face = lever.getFace().toString().toLowerCase();
        String facing = lever.getFacing().toString().toLowerCase();

        setLeverPowered(leverBlock, face, facing, true);
        node.poweredShape = readShape(switchRailBlock);

        setLeverPowered(leverBlock, face, facing, false);
        node.unpoweredShape = readShape(switchRailBlock);

        setLeverPowered(leverBlock, face, facing, originalPowered);
    }

    private static void setLeverPowered(Block leverBlock, String face, String facing, boolean powered) {
        String command = "setblock " + leverBlock.getX() + " " + leverBlock.getY() + " " + leverBlock.getZ()
                + " minecraft:lever[face=" + face + ",facing=" + facing + ",powered=" + powered + "]";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private static String readShape(Block block) {
        BlockData data = block.getBlockData();
        return (data instanceof Rail) ? ((Rail) data).getShape().toString() : null;
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
