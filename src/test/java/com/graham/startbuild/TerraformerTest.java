package com.graham.startbuild;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new-ground-height arithmetic of {@link Terraformer#targetHeights} and its distance field. */
class TerraformerTest {

    private static final int R = Terraformer.MAX_RADIUS;
    private static final int F = 10;                 // footprint size
    private static final int W = F + 2 * R;          // grid size
    private static final int PAD = 64;

    /** A W x W grid whose ground is f(x, z); the build stands on (R, R) .. (R+F-1, R+F-1). */
    private static int[] grid(IntBinaryOperator f) {
        int[] g = new int[W * W];
        for (int z = 0; z < W; z++) for (int x = 0; x < W; x++) g[z * W + x] = f.applyAsInt(x, z);
        return g;
    }

    private static boolean[] footprint() {
        boolean[] s = new boolean[W * W];
        for (int z = R; z < R + F; z++) for (int x = R; x < R + F; x++) s[z * W + x] = true;
        return s;
    }

    private static final double[] DIST = Terraformer.distanceField(footprint(), W, W);

    private static int[] noCap() {
        int[] c = new int[W * W];
        Arrays.fill(c, Integer.MAX_VALUE);
        return c;
    }

    private static int[] run(int[] ground, boolean[] wet, int minRadius, int[] blend) {
        return Terraformer.targetHeights(ground, wet, new int[W * W], DIST, noCap(), W, W, PAD, minRadius, blend);
    }

    private static double dist(int x, int z) {
        return Terraformer.distToFoot(x, z, R, R, R + F - 1, R + F - 1);
    }

    /** No two neighbouring columns of the new ground differ by more than `max` (no cliffs, no creases). */
    private static void assertSmooth(int[] t, int max) {
        for (int z = 0; z < W; z++) {
            for (int x = 0; x + 1 < W; x++) {
                int a = t[z * W + x], b = t[z * W + x + 1];
                if (a == Terraformer.UNKNOWN || b == Terraformer.UNKNOWN) continue;
                assertTrue(Math.abs(a - b) <= max, "step of " + Math.abs(a - b) + " at " + x + "," + z);
            }
        }
    }

    @Test
    void distanceFieldIsEuclideanEnough() {
        assertEquals(0.0, DIST[R * W + R]);
        assertEquals(5.0, DIST[(R + 3) * W + R - 5], 1e-9);          // 5 straight out to the west
        double diag = DIST[(R - 4) * W + R - 3];                       // 3 west, 4 north: true distance 5
        assertTrue(Math.abs(diag - 5) < 0.7, "diagonal " + diag);
    }

    @Test
    void flatGroundIsLeftAlone() {
        int[] t = run(grid((x, z) -> PAD), new boolean[W * W], 12, null);
        for (int v : t) assertEquals(PAD, v);
    }

    @Test
    void footprintIsFlatAndAHillIsEasedBackSmoothly() {
        int[] ground = grid((x, z) -> PAD + (int) Math.round(dist(x, z) * 0.5));
        int[] blend = new int[1];
        int[] t = run(ground, new boolean[W * W], 12, blend);
        for (int z = 0; z < W; z++) {
            for (int x = 0; x < W; x++) {
                int i = z * W + x;
                if (dist(x, z) == 0) assertEquals(PAD, t[i], "the build stands on flat ground");
                assertTrue(t[i] <= ground[i] && t[i] >= PAD, "a hill is only cut, never raised or dug below the pad");
                if (DIST[i] > blend[0]) assertEquals(ground[i], t[i], "beyond the blend zone the land is untouched");
            }
        }
        assertEquals(12, blend[0], "a 1-in-2 hill fits the configured radius");
        // The eased curve plus the hill's own rise can round to an occasional 2-block step - ordinary
        // Minecraft terrain - but never a cliff.
        assertSmooth(t, 2);
    }

    @Test
    void dipIsFilledWithASmoothEmbankment() {
        int[] ground = grid((x, z) -> PAD - 6);
        int[] t = run(ground, new boolean[W * W], 12, null);
        for (int i = 0; i < t.length; i++) assertTrue(t[i] >= ground[i], "a dip is only filled, never cut");
        assertSmooth(t, 1);
    }

    @Test
    void steepSiteWidensTheBlendInsteadOfLeavingACliff() {
        int[] ground = grid((x, z) -> dist(x, z) == 0 ? PAD : PAD + 20);
        int[] blend = new int[1];
        int[] t = run(ground, new boolean[W * W], 12, blend);
        assertTrue(blend[0] > 12, "the zone grows on a steep site, was " + blend[0]);
        assertSmooth(t, 2);
    }

    @Test
    void groundStaysUnderAnOverhangingPartOfTheBuild() {
        int[] ground = grid((x, z) -> PAD + 8);
        int[] cap = noCap();
        int i = (R - 2) * W + R + 3;                                  // just outside the standing footprint
        cap[i] = PAD + 2;                                             // a balcony 2 above the pad overhead
        int[] t = Terraformer.targetHeights(ground, new boolean[W * W], new int[W * W], DIST, cap, W, W, PAD, 12, null);
        assertTrue(t[i] <= PAD + 2, "ground " + t[i] + " would hit the build");
    }

    @Test
    void waterIsUntouchedAndCutsStayAboveIt() {
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
        int[] t = run(grid((x, z) -> x < 3 ? Terraformer.UNKNOWN : PAD), new boolean[W * W], 12, null);
        for (int z = 0; z < W; z++) for (int x = 0; x < 3; x++) assertEquals(Terraformer.UNKNOWN, t[z * W + x]);
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
