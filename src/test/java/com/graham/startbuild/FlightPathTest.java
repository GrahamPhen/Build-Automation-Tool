package com.graham.startbuild;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flight A* of {@link FlightPath}. */
class FlightPathTest {

    private static final int[] BOX = {-20, 0, -20, 20, 20, 20};

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static FlightPath.Goal at(int ex, int ey, int ez) {
        return (x, y, z) -> x == ex && y == ey && z == ez;
    }

    @Test
    void openAirIsAStraightManhattanPath() {
        List<int[]> p = FlightPath.find(0, 5, 0, 6, 5, 4, BOX, (x, y, z) -> 0, at(6, 5, 4), 10000);
        assertNotNull(p);
        assertEquals(10, p.size());
        int[] last = p.get(p.size() - 1);
        assertEquals(6, last[0]);
        assertEquals(4, last[2]);
    }

    @Test
    void goesRoundAWallThroughTheDoorway() {
        // A wall at x = 3 from y 0..20, z -20..20, with a 1x2 doorway at z = 7, y 5..6.
        Set<String> wall = new HashSet<>();
        for (int y = 0; y <= 20; y++) for (int z = -20; z <= 20; z++) {
            if (z == 7 && (y == 5 || y == 6)) continue;
            wall.add(key(3, y, z));
        }
        FlightPath.Cells cells = (x, y, z) -> wall.contains(key(x, y, z)) ? FlightPath.BLOCKED : 0;
        List<int[]> p = FlightPath.find(0, 5, 0, 6, 5, 0, BOX, cells, at(6, 5, 0), 20000);
        assertNotNull(p);
        boolean usedDoor = false;
        for (int[] c : p) {
            assertTrue(!wall.contains(key(c[0], c[1], c[2])) && !wall.contains(key(c[0], c[1] + 1, c[2])),
                    "path must not pass through the wall");
            if (c[0] == 3) usedDoor = c[2] == 7 && c[1] == 5;
        }
        assertTrue(usedDoor, "path crosses the wall only at the doorway");
    }

    @Test
    void shutInWithBreakableWallsDigsTheFewestBlocks() {
        // A closed 3x3x3 room (interior 0..2) of breakable blocks (cost 8), inside an unbreakable shell
        // except for the room's east wall, which is ours to break.
        FlightPath.Cells cells = (x, y, z) -> {
            boolean inRoom = x >= 0 && x <= 2 && y >= 1 && y <= 3 && z >= 0 && z <= 2;
            boolean shell = x >= -1 && x <= 3 && y >= 0 && y <= 4 && z >= -1 && z <= 3;
            if (inRoom) return 0;
            if (shell) return x == 3 ? 8 : FlightPath.BLOCKED;
            return 0;
        };
        List<int[]> p = FlightPath.find(1, 1, 1, 8, 1, 1, BOX, cells, at(8, 1, 1), 20000);
        assertNotNull(p);
        int dug = 0;
        for (int[] c : p) {
            if (c[0] == 3) dug++;
        }
        assertEquals(1, dug, "one step through the breakable wall (feet+head cells)");
    }

    @Test
    void sealedInUnbreakableWallsHasNoPath() {
        FlightPath.Cells cells = (x, y, z) -> {
            boolean inRoom = x >= 0 && x <= 2 && y >= 1 && y <= 3 && z >= 0 && z <= 2;
            boolean shell = x >= -1 && x <= 3 && y >= 0 && y <= 4 && z >= -1 && z <= 3;
            return inRoom ? 0 : shell ? FlightPath.BLOCKED : 0;
        };
        assertNull(FlightPath.find(1, 1, 1, 8, 1, 1, BOX, cells, at(8, 1, 1), 20000));
    }

    @Test
    void packRoundTripsNegativeCoordinates() {
        int[][] cases = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-12345, 70, 67890}};
        for (int[] c : cases) {
            long k = FlightPath.pack(c[0], c[1], c[2]);
            assertEquals(c[0], FlightPath.ux(k));
            assertEquals(c[1], FlightPath.uy(k));
            assertEquals(c[2], FlightPath.uz(k));
        }
    }
}
