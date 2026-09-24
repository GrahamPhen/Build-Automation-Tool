package com.graham.startbuild;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The earthworks estimate that ranks /findsite sites by how much terraforming they need. */
class PlacementFinderTest {

    private static final int SIZE = 60;

    @Test
    void flatGroundNeedsNoEarthworks() {
        int[] surf = new int[SIZE * SIZE];
        Arrays.fill(surf, 64);
        assertEquals(0.0, PlacementFinder.ringEarthworks(surf, SIZE, 20, 20, 10, 10, 64));
    }

    @Test
    void aHillsideCostsMoreThanAGentleSlope() {
        int[] gentle = new int[SIZE * SIZE], steep = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                gentle[z * SIZE + x] = 64 + x / 4;     // 1 up per 4 across: the blend slope absorbs it
                steep[z * SIZE + x] = 64 + x;          // 1 up per 1 across: a hillside
            }
        }
        double g = PlacementFinder.ringEarthworks(gentle, SIZE, 20, 20, 10, 10, 64 + 20 / 4);
        double s = PlacementFinder.ringEarthworks(steep, SIZE, 20, 20, 10, 10, 64 + 20);
        assertTrue(s > g * 3, "steep " + s + " vs gentle " + g);
    }

    @Test
    void unloadedColumnsAreIgnored() {
        int[] surf = new int[SIZE * SIZE];
        Arrays.fill(surf, Integer.MIN_VALUE);
        assertEquals(0.0, PlacementFinder.ringEarthworks(surf, SIZE, 20, 20, 10, 10, 64));
    }
}
