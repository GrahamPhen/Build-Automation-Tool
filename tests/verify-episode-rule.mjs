// Regression guard for the session logic that cannot be checked in-game without a multi-hour run.
//
// The episode boundary is the important one. Baritone's IBuilderProcess.getMinLayer() numbering is not
// documented anywhere reachable, and IBuilderProcess.buildOpenLitematic(int) is declared void so there is
// no way to ask whether a build request was accepted. An earlier version compared the reported layer
// against episodeNumber * episodeLayers, which is only correct if the reported number is 1-based while
// building - if that assumption is wrong, EVERY episode runs one layer long and overlaps the next one for
// ever, and the "Next: layers N-M" message is wrong too.
//
// The fix measures the episode's length as a DISTANCE from the lowest layer number observed during that
// episode. Subtracting two readings of the same method cancels out whatever base Baritone counts from, so
// the rule is correct under either convention. These tests pin that down.
//
// Run: node tests/verify-episode-rule.mjs

let failures = 0;
function check(name, actual, expected) {
    const a = JSON.stringify(actual);
    const e = JSON.stringify(expected);
    if (a === e) {
        console.log(`  PASS  ${name}`);
    } else {
        failures++;
        console.log(`  FAIL  ${name}\n          expected ${e}\n          actual   ${a}`);
    }
}

// Mirrors the episode branch of tickBuilding().
//   episodeLowestLayer starts at MAX_VALUE and tracks the MINIMUM layer number seen.
//   stop once (layer - episodeLowestLayer) >= episodeLayers.
function simulateEpisode(reportedLayers, episodeLayers) {
    let lowest = Number.MAX_SAFE_INTEGER;
    let built = 0;
    for (const layer of reportedLayers) {
        if (layer < 0) continue;
        if (layer < lowest) lowest = layer;
        built++;
        if (layer - lowest >= episodeLayers) {
            // This reading is the FIRST layer of the NEXT episode: the current one is finished.
            return { stoppedAtReportedLayer: layer, layersBuilt: built - 1, lowest };
        }
    }
    return { stoppedAtReportedLayer: null, layersBuilt: built, lowest };
}

// The old, absolute rule, for contrast.
function oldEpisodeStop(reportedLayers, episodeNumber, episodeLayers) {
    const stopLayer = episodeNumber * episodeLayers;
    let built = 0;
    for (const layer of reportedLayers) {
        if (layer > stopLayer) return { stoppedAtReportedLayer: layer, layersBuilt: built };
        built++;
    }
    return { stoppedAtReportedLayer: null, layersBuilt: built };
}

console.log('episode of 3 layers, reported 1-based (reported = 0-based layer + 1)');
// Baritone building schematic layers 0,1,2,3,4 -> reported 1,2,3,4,5
check('stops as soon as layer 4 (the 4th) is reported', simulateEpisode([1, 2, 3, 4, 5], 3), {
    stoppedAtReportedLayer: 4, layersBuilt: 3, lowest: 1,
});

console.log('\nthe same episode if the convention is 0-based');
// schematic layers 0,1,2,3,4 -> reported 0,1,2,3,4
check('stops as soon as layer 3 (the 4th) is reported', simulateEpisode([0, 1, 2, 3, 4], 3), {
    stoppedAtReportedLayer: 3, layersBuilt: 3, lowest: 0,
});

console.log('\nboth conventions build exactly the requested 3 layers');
const oneBased = simulateEpisode([1, 2, 3, 4, 5], 3).layersBuilt;
const zeroBased = simulateEpisode([0, 1, 2, 3, 4], 3).layersBuilt;
check('1-based and 0-based agree', oneBased === zeroBased, true);
check('and both equal episodeLayers', [oneBased, zeroBased], [3, 3]);

console.log('\nthe old absolute rule was right only for ONE numbering convention');
// This is the honest finding. The old rule (stop when reported > episodeNumber * episodeLayers) happens
// to give exactly the right answer when the reported layer is 1-based, because it stops on the FIRST
// reading of the next layer - a partial layer at worst, not a whole extra one. So it was not the
// permanent overlap it was reported as. But it silently depends on an undocumented convention, and under
// a 0-based reading the same code builds one layer too many. The distance rule removes the dependency.
check('old rule under a 1-based reading: 3 layers (accidentally correct)',
    oldEpisodeStop([1, 2, 3, 4, 5], 1, 3).layersBuilt, 3);
check('old rule under a 0-based reading: 4 layers (one too many)',
    oldEpisodeStop([0, 1, 2, 3, 4], 1, 3).layersBuilt, 4);
check('new rule is 3 either way',
    [simulateEpisode([1, 2, 3, 4, 5], 3).layersBuilt, simulateEpisode([0, 1, 2, 3, 4], 3).layersBuilt],
    [3, 3]);

console.log('\nepisode 2 must start where episode 1 ended, with no overlap');
// episodesDone = 1 after episode 1, so episode 2 sets startAtLayer = 1*3 = 3 (0-based) and sees 4,5,6,...
check('episode 2 (reported 4,5,6,7) builds 3 layers',
    simulateEpisode([4, 5, 6, 7], 3), { stoppedAtReportedLayer: 7, layersBuilt: 3, lowest: 4 });
check('episode 1 ended at reported 4, episode 2 started at reported 4 - contiguous, not overlapping',
    [simulateEpisode([1, 2, 3, 4], 3).stoppedAtReportedLayer, 4], [4, 4]);

console.log('\nrobustness');
check('a layer reading of -1 (no build running) is ignored',
    simulateEpisode([-1, -1, 1, 2, 3, 4], 3).layersBuilt, 3);
check('a single-layer episode stops after one layer',
    simulateEpisode([1, 2], 1), { stoppedAtReportedLayer: 2, layersBuilt: 1, lowest: 1 });
check('starts late (first sample already at layer 5) still builds the right count',
    simulateEpisode([5, 6, 7, 8], 3), { stoppedAtReportedLayer: 8, layersBuilt: 3, lowest: 5 });

console.log('\nstall gating: two clocks, so a walking builder is not mistaken for a wedged one');
// Mirrors tickBuilding()'s stall branch.
//   lastPlacement  = max(lastPlacementTick, max(buildStartTick, noProgressAnchor))
//   lastActivity   = max(lastPlacement, lastMovementTick)
//   restart when (now - lastActivity) > stallTicks                     [frozen]
//             or (now - lastPlacement) > stallTicks * FACTOR          [moving but not building]
const FACTOR = 4;
function shouldRestart(now, lastPlacement, lastMovement, stallSeconds) {
    const stallTicks = stallSeconds * 20;
    const lastActivity = Math.max(lastPlacement, lastMovement);
    return (now - lastActivity) > stallTicks || (now - lastPlacement) > stallTicks * FACTOR;
}

const T = 10000;               // some tick
const S = 180;                 // stallRecoverSeconds
const OVER = 21;               // one second past a threshold, since the comparisons are strict
check('placing normally: no restart', shouldRestart(T, T - 40, T - 5, S), false);
check('walking to the far side for 3 min with no placement: no restart (was a false positive)',
    shouldRestart(T, T - 3 * 20 * 60, T - 10, S), false);
check('exactly 180.0s frozen: not yet (comparison is strictly greater)',
    shouldRestart(T, T - S * 20, T - S * 20, S), false);
check('wedged and standing still for just over 3 min: restart',
    shouldRestart(T, T - (S * 20 + OVER), T - (S * 20 + OVER), S), true);
check('walking in circles for just over 12 min without placing: restart',
    shouldRestart(T, T - (S * FACTOR * 20 + OVER), T - 10, S), true);
check('walking in circles for 11 min without placing: not yet',
    shouldRestart(T, T - 11 * 20 * 60, T - 10, S), false);

console.log('');
if (failures > 0) {
    console.log(`${failures} CHECK(S) FAILED`);
    process.exit(1);
}
console.log('ALL CHECKS PASSED');
