package com.pandymic.dev.mcblockreporter;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Traces a connected rail network from a starting point via BFS. Read-only
 * -- never places or breaks a block.
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
 * active branch is controlled by that lever.
 */
public class RailNetworkScanner {

    private static final int SEARCH_RADIUS = 3;
    private static final int MAX_NODES = 5000;

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

    public static class Node {
        public final int x, y, z;
        public final String material;
        public final String shape;
        public boolean isIntersection = false;
        public boolean isJunction = false;
        public Pos lever = null;

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
        Result result = new Result();
        Block start = findStartingRail(world, x, y, z);
        if (null == start) {
            result.error = "No rail found within " + SEARCH_RADIUS + " blocks of "
                    + x + "," + (null != y ? y : "<any y>") + "," + z;
            return result;
        }
        traverse(start, result);
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

    // Positions checked for a controlling lever near an intersection: this
    // block and one above, in the 4 cardinal directions (including 0,0 --
    // directly on top of the intersection).
    private static final int[][] LEVER_SEARCH_OFFSETS = {
            {0, 0, 0}, {0, 1, 0},
            {1, 0, 0}, {1, 1, 0}, {-1, 0, 0}, {-1, 1, 0},
            {0, 0, 1}, {0, 1, 1}, {0, 0, -1}, {0, 1, -1},
    };

    private static void traverse(Block start, Result result) {
        Set<String> visited = new HashSet<>();
        Set<String> edgeKeys = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(posKey(start));

        while (!queue.isEmpty()) {
            if (result.nodes.size() >= MAX_NODES) {
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
        for (int[] off : LEVER_SEARCH_OFFSETS) {
            Block candidate = block.getRelative(off[0], off[1], off[2]);
            if (Material.LEVER == candidate.getType()) {
                return candidate;
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
