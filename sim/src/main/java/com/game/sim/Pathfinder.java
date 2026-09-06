package com.game.sim;

/**
 * Path search over the terrain grid.
 *
 * <p>Phase 7 lands the fine-grained A* piece with the same "one shared
 * working buffer, no allocation in the hot path" shape as everything else in
 * sim. It runs on the tile grid, treats {@link TileType#isWalkable} tiles as
 * traversable, and follows straight octile costs (10 for orthogonal steps,
 * 14 for diagonals - the standard approximation for {@code sqrt(2)} scaled
 * up by 10 to keep everything in integers).
 *
 * <p>Roads will drop the traversal cost of any tile the road bit is set on
 * to make units on a road move faster, per the phase 8 plan; phase 7 does
 * not have roads yet, so the road-cost hook here is a stub that returns
 * the base cost regardless. That hook exists now so phase 8 wiring is a
 * two-line change instead of a search rewrite.
 *
 * <p>The plan calls for hierarchical portals - A* over a chunk graph, flow
 * fields inside a chunk. This first implementation is flat A*: enough to
 * unit-test the traversal rules and cost function, and correct enough for
 * road-length queries in phase 8. Portalisation and per-chunk flow fields
 * land alongside their first caller in phase 10 when armies actually need
 * to march across the whole map.
 */
public final class Pathfinder {

    private static final int ORTHOGONAL_COST = 10;
    private static final int DIAGONAL_COST = 14;
    /** Divisor applied to step cost when moving onto a road tile. */
    private static final int ROAD_DISCOUNT = 2;
    /** Sentinel for "no predecessor" / "unexplored". */
    private static final int UNSET = -1;

    private final int worldSize;

    // Per-tile scratch, allocated once per instance and reused across searches.
    // No allocation in the hot path, and no locking - one thread runs searches.
    private final int[] gScore;
    private final int[] cameFrom;
    /**
     * Rising counter that invalidates every per-tile buffer between searches
     * (visited/closed/gScore). Ordinary boolean {@code closed[]} would leak
     * state from one search into the next, since a full O(worldSize^2) clear
     * before every findPath is exactly what the stamp exists to avoid.
     */
    private final int[] visitStamp;
    private final int[] closedStamp;
    private int currentStamp;

    /** Min-heap of open tiles, keyed by fScore. Array-backed binary heap. */
    private final int[] openHeap;
    /** Parallel to openHeap; the fScore each entry was queued with. */
    private final int[] openFScore;
    private int openSize;

    private int[] resultPath;
    private int resultPathLength;

    public Pathfinder(int worldSize) {
        if (worldSize <= 0) {
            throw new IllegalArgumentException("worldSize must be positive, got " + worldSize);
        }
        this.worldSize = worldSize;
        int tiles = worldSize * worldSize;
        gScore = new int[tiles];
        cameFrom = new int[tiles];
        openHeap = new int[tiles];
        openFScore = new int[tiles];
        visitStamp = new int[tiles];
        closedStamp = new int[tiles];
        resultPath = new int[Math.max(64, worldSize * 2)];
    }

    /** Convenience overload: no road network. Traversal is uniform cost. */
    public boolean findPath(World world, int sx, int sz, int tx, int tz) {
        return findPath(world, null, sx, sz, tx, tz);
    }

    /**
     * Finds a path from ({@code sx, sz}) to ({@code tx, tz}) on the world's
     * walkable tiles. Returns true on success; the path can then be walked
     * from {@link #getResultLength()} - 1 down to 0, or read from
     * {@link #getResultPath()} which stores tile indices in that order.
     *
     * @param roads optional road network; road tiles cost half a step, which
     *     is what makes units prefer them
     */
    public boolean findPath(World world, Roads roads, int sx, int sz, int tx, int tz) {
        resultPathLength = 0;
        if (!world.inBounds(sx, sz) || !world.inBounds(tx, tz)) {
            return false;
        }
        if (!TileType.isWalkable(world.typeAt(sx, sz))
            || !TileType.isWalkable(world.typeAt(tx, tz))) {
            return false;
        }
        if (sx == tx && sz == tz) {
            resultPath[0] = world.index(sx, sz);
            resultPathLength = 1;
            return true;
        }

        currentStamp++;
        if (currentStamp == 0) {
            // Wrap around once every four billion searches; reset the stamps.
            java.util.Arrays.fill(visitStamp, 0);
            java.util.Arrays.fill(closedStamp, 0);
            currentStamp = 1;
        }
        openSize = 0;

        int start = world.index(sx, sz);
        int goal = world.index(tx, tz);
        gScore[start] = 0;
        cameFrom[start] = UNSET;
        visitStamp[start] = currentStamp;
        pushOpen(start, heuristic(sx, sz, tx, tz));

        while (openSize > 0) {
            int current = popOpen();
            if (current == goal) {
                buildResult(world, start, goal);
                return true;
            }
            if (closedStamp[current] == currentStamp) {
                continue;
            }
            closedStamp[current] = currentStamp;

            int cx = current % worldSize;
            int cz = current / worldSize;
            expandNeighbours(world, roads, current, cx, cz, tx, tz);
        }
        return false;
    }

    /** The last search's path, as tile indices from start to goal. */
    public int[] getResultPath() {
        return resultPath;
    }

    public int getResultLength() {
        return resultPathLength;
    }

    /**
     * Step cost into {@code neighbour}. A road tile costs half a step so
     * A* actively prefers going the long way round on a road over a
     * shorter path across open ground. The heuristic still uses the full
     * ORTHOGONAL_COST which stays admissible even with the discount.
     */
    private int stepCost(Roads roads, int neighbour, boolean diagonal) {
        int base = diagonal ? DIAGONAL_COST : ORTHOGONAL_COST;
        if (roads != null && roads.isRoad(neighbour)) {
            return base / ROAD_DISCOUNT;
        }
        return base;
    }

    private void expandNeighbours(World world, Roads roads, int current, int cx, int cz, int tx, int tz) {
        int gCurrent = gScore[current];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = cx + dx;
                int nz = cz + dz;
                if (!world.inBounds(nx, nz)) {
                    continue;
                }
                if (!TileType.isWalkable(world.typeAt(nx, nz))) {
                    continue;
                }
                // Diagonal moves are only legal if both orthogonal neighbours
                // are also walkable - a diagonal that squeezes between two
                // impassable corners looks like it clips through terrain.
                boolean diagonal = (dx != 0) && (dz != 0);
                if (diagonal) {
                    if (!TileType.isWalkable(world.typeAt(cx + dx, cz))
                        || !TileType.isWalkable(world.typeAt(cx, cz + dz))) {
                        continue;
                    }
                }

                int neighbour = world.index(nx, nz);
                if (closedStamp[neighbour] == currentStamp) {
                    continue;
                }

                int tentative = gCurrent + stepCost(roads, neighbour, diagonal);
                if (visitStamp[neighbour] != currentStamp || tentative < gScore[neighbour]) {
                    visitStamp[neighbour] = currentStamp;
                    cameFrom[neighbour] = current;
                    gScore[neighbour] = tentative;
                    int f = tentative + heuristic(nx, nz, tx, tz);
                    pushOpen(neighbour, f);
                }
            }
        }
    }

    /** Octile heuristic, matching the octile step cost so A* stays optimal. */
    private static int heuristic(int ax, int az, int bx, int bz) {
        int dx = Math.abs(ax - bx);
        int dz = Math.abs(az - bz);
        int diag = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diag;
        return DIAGONAL_COST * diag + ORTHOGONAL_COST * straight;
    }

    private void buildResult(World world, int start, int goal) {
        int length = 0;
        int cursor = goal;
        while (cursor != UNSET) {
            if (length >= resultPath.length) {
                // Grow: doubles rather than +1, so a rare very long path is
                // paid for once and never again on this instance.
                int[] bigger = new int[resultPath.length * 2];
                System.arraycopy(resultPath, 0, bigger, 0, length);
                resultPath = bigger;
            }
            resultPath[length++] = cursor;
            cursor = cameFrom[cursor];
        }
        // Path is stored goal->start; reverse in place so callers read
        // start->goal, which is the intuitive order.
        for (int i = 0, j = length - 1; i < j; i++, j--) {
            int tmp = resultPath[i];
            resultPath[i] = resultPath[j];
            resultPath[j] = tmp;
        }
        resultPathLength = length;
    }

    // ---- binary heap operations ----

    private void pushOpen(int tile, int f) {
        int i = openSize++;
        openHeap[i] = tile;
        openFScore[i] = f;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (openFScore[parent] <= openFScore[i]) {
                break;
            }
            swapHeap(i, parent);
            i = parent;
        }
    }

    private int popOpen() {
        int result = openHeap[0];
        openSize--;
        if (openSize > 0) {
            openHeap[0] = openHeap[openSize];
            openFScore[0] = openFScore[openSize];
            int i = 0;
            while (true) {
                int left = i * 2 + 1;
                int right = left + 1;
                int smallest = i;
                if (left < openSize && openFScore[left] < openFScore[smallest]) {
                    smallest = left;
                }
                if (right < openSize && openFScore[right] < openFScore[smallest]) {
                    smallest = right;
                }
                if (smallest == i) {
                    break;
                }
                swapHeap(i, smallest);
                i = smallest;
            }
        }
        return result;
    }

    private void swapHeap(int a, int b) {
        int t = openHeap[a];
        openHeap[a] = openHeap[b];
        openHeap[b] = t;
        int f = openFScore[a];
        openFScore[a] = openFScore[b];
        openFScore[b] = f;
    }
}
