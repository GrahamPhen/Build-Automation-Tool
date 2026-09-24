package com.graham.startbuild;

import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new-ground-height arithmetic of {@link Terraformer#targetHeights}. */
class TerraformerTest {

    private static final int R = Terraformer.MAX_RADIUS;
    private static final int F = 10;                 // footprint size
    private static final int W = F + 2 * R;          // grid size
    private static final int PAD = 64;

    /** A W x W grid whose ground is f(x, z); footprint at (R, R) .. (R+F-1, R+F-1). */
    private static int[] grid(IntBinaryOperator f) {
        int[] g = new int[W * W];
        for (int z = 0; z < W; z++) for (int x = 0; x < W; x++) g[z * W + x] = f.applyAsInt(x, z);
        return g;
    }

    private static int[] run(int[] ground, boolean[] wet, int minRadius, int[] blend) {
        return Terraformer.targetHeights(ground, wet, new int[W * W], W, W, R, R, F, F, PAD, minRadius, blend);
    }

    private static double dist(int x, int z) {
        return Terraformer.distToFoot(x, z, R, R, R + F - 1, R + F - 1);
    }

    @Test
    void flatGroundIsLeftAlone() {
        int[] ground = grid((x, z) -> PAD);
        int[] t = run(ground, new boolean[W * W], 12, null);
        for (int i = 0; i < t.length; i++) assertEquals(PAD, t[i]);
    }

    @Test
    void footprintIsFlatAndBanksEaseBackToTheHill() {
        // A hill rising 1 block per block westward... everywhere: ground = PAD + distance from the footprint.
        int[] ground = grid((x, z) -> PAD + (int) Math.round(dist(x, z)));
        int[] blend = new int[1];
        int[] t = run(ground, new boolean[W * W], 12, blend);
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                int i = z * W + x;
                double d = dist(x, z);
                if (d == 0) assertEquals(PAD, t[i], "footprint must be flat at the pad");
                assertTrue(t[i] <= ground[i], "a hill is only ever cut, never raised");
                assertTrue(t[i] >= PAD, "never dug below the pad");
                if (d > blend[0]) assertEquals(ground[i], t[i], "beyond the blend zone the land is untouched");
            }
        }
        // Slope 1 at radius 12 fits exactly: the blend radius stays at the configured 12.
        assertEquals(12, blend[0]);
    }

    @Test
    void dipIsFilledWithAnEmbankment() {
        int[] ground = grid((x, z) -> dist(x, z) == 0 ? PAD - 6 : PAD - 6);   // a hollow 6 below the pad
        int[] t = run(ground, new boolean[W * W], 12, null);
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                int i = z * W + x;
                assertTrue(t[i] >= ground[i], "a dip is only ever filled, never cut");
                double d = dist(x, z);
                if (d == 0) assertEquals(PAD, t[i]);
                if (d >= 1 && d <= 12) assertTrue(PAD - t[i] <= Math.floor(d * 0.5 + 0.9) + 1e-9,
                        "the embankment slopes no steeper than 1 in 2 here");
            }
        }
    }

    @Test
    void steepSiteWidensTheBlendInsteadOfLeavingACliff() {
        int[] ground = grid((x, z) -> dist(x, z) == 0 ? PAD : PAD + 20);   // a 20-block cliff all round
        int[] blend = new int[1];
        int[] t = run(ground, new boolean[W * W], 12, blend);
        assertTrue(blend[0] > 12, "the zone grows on a steep site, was " + blend[0]);
        // At the zone's edge the new ground meets the natural ground: at most an ordinary 1-block step, no cliff.
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                if (Math.abs(dist(x, z) - blend[0]) < 0.5) {
                    assertTrue(Math.abs(ground[z * W + x] - t[z * W + x]) <= 1,
                            "step at the edge " + (ground[z * W + x] - t[z * W + x]));
                }
            }
        }
    }

    @Test
    void waterIsUntouchedAndCutsStayAboveIt() {
        // Ground 5 above the pad, with a pond (surface PAD + 3) just east of the footprint.
        int[] ground = grid((x, z) -> PAD + 5);
        boolean[] wet = new boolean[W * W];
        int px = R + F + 3;
        for (int z = R; z < R + F; z++) {
            ground[z * W + px] = PAD + 3;
            wet[z * W + px] = true;
        }
        int[] t = run(ground, wet, 12, null);
        for (int z = R; z < R + F; z++) {
            assertEquals(PAD + 3, t[z * W + px], "water column untouched");
            for (int x = px - 2; x <= px + 2; x++) {
                if (x == px || dist(x, z) == 0) continue;
                assertTrue(t[z * W + x] >= PAD + 3, "no cut below the water surface next to it");
            }
        }
    }

    @Test
    void unknownColumnsStayUnknown() {
        int[] ground = grid((x, z) -> x < 3 ? Terraformer.UNKNOWN : PAD);
        int[] t = run(ground, new boolean[W * W], 12, null);
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < 3; x++) assertEquals(Terraformer.UNKNOWN, t[z * W + x]);
        }
    }

    @Test
    void noiseStaysInRange() {
        for (int x = -50; x < 50; x += 7) {
            for (int z = -50; z < 50; z += 3) {
                double n = Terraformer.noise(x, z);
                assertTrue(n >= 0 && n < 1, "noise " + n);
            }
        }
    }
}
