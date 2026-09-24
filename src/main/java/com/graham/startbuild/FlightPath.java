package com.graham.startbuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* for a flying player over a block grid - no Minecraft types, so it is unit-tested on its own.
 *
 * A node is the cell the player's feet are in; the player also fills the cell above (one wide, two tall).
 * Moves are the six face directions. Each cell reports a cost: {@link #BLOCKED}, 0 (free air), or an
 * extra cost to break through it (the builder only allows that for its own blocks).
 */
final class FlightPath {

    static final int BLOCKED = -1;

    interface Cells {
        /** @return BLOCKED, 0 for free, or the extra cost of breaking this cell to pass. */
        int cost(int x, int y, int z);
    }

    interface Goal {
        boolean reached(int x, int y, int z);
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private FlightPath() {
    }

    /**
     * @param box {minX, minY, minZ, maxX, maxY, maxZ} the search stays inside (inclusive)
     * @return the cells to pass through after the start, ending at one where goal.reached; null if none
     *         within maxNodes expansions
     */
    static List<int[]> find(int sx, int sy, int sz, int ex, int ey, int ez, int[] box, Cells cells, Goal goal,
                            int maxNodes) {
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        Map<Long, Integer> g = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long s0 = pack(sx, sy, sz);
        g.put(s0, 0);
        open.add(new long[]{h(sx, sy, sz, ex, ey, ez), s0});
        int expanded = 0;
        while (!open.isEmpty()) {
            long[] top = open.poll();
            long key = top[1];
            int x = ux(key), y = uy(key), z = uz(key);
            int gc = g.get(key);
            if (top[0] > gc + h(x, y, z, ex, ey, ez)) continue;       // stale entry
            if (goal.reached(x, y, z)) {
                List<int[]> path = new ArrayList<>();
                for (Long k = key; k != null && k != s0; k = parent.get(k)) path.add(0, new int[]{ux(k), uy(k), uz(k)});
                return path;
            }
            if (++expanded > maxNodes) return null;
            for (int[] dir : DIRS) {
                int nx = x + dir[0], ny = y + dir[1], nz = z + dir[2];
                if (nx < box[0] || ny < box[1] || nz < box[2] || nx > box[3] || ny > box[4] || nz > box[5]) continue;
                int feet = cells.cost(nx, ny, nz);
                if (feet == BLOCKED) continue;
                int head = cells.cost(nx, ny + 1, nz);
                if (head == BLOCKED) continue;
                long nk = pack(nx, ny, nz);
                int ng = gc + 1 + feet + head;
                Integer old = g.get(nk);
                if (old != null && old <= ng) continue;
                g.put(nk, ng);
                parent.put(nk, key);
                open.add(new long[]{ng + h(nx, ny, nz, ex, ey, ez), nk});
            }
        }
        return null;
    }

    private static long h(int x, int y, int z, int ex, int ey, int ez) {
        return Math.abs(x - ex) + Math.abs(y - ey) + Math.abs(z - ez);
    }

    // 26 bits x, 26 bits z, 12 bits y (signed), like BlockPos.asLong.
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int ux(long k) {
        return (int) (k >> 38);
    }

    static int uy(long k) {
        return (int) (k << 52 >> 52);
    }

    static int uz(long k) {
        return (int) (k << 26 >> 38);
    }
}
