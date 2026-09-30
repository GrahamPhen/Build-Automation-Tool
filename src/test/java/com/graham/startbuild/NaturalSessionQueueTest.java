package com.graham.startbuild;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NaturalSessionQueueTest {
    @TempDir Path dir;

    @Test void exhaustedSiteSearchRestoresItsConsumedHeadAndHoldsTheExactRemainder() throws Exception {
        Path queue = dir.resolve("queue"), hold = dir.resolve("hold");
        Files.write(queue, List.of("second plains", "Briarwood_House_TIER_2_ plains"));
        NaturalSession.holdFailedQueuedTake(queue, hold, "failed plains");
        assertFalse(Files.exists(queue), "no further queued take may be dispatched");
        assertEquals(List.of("failed plains", "second plains", "Briarwood_House_TIER_2_ plains"), Files.readAllLines(hold));
    }

    @Test void preexistingHoldIsNeverOverwrittenAndFailedHeadRemainsOnDisk() throws Exception {
        Path queue = dir.resolve("queue"), hold = dir.resolve("hold");
        Files.write(queue, List.of("next plains"));
        Files.writeString(hold, "another task's preserved queue\n");
        byte[] preserved = Files.readAllBytes(hold);
        NaturalSession.holdFailedQueuedTake(queue, hold, "failed plains");
        assertArrayEquals(preserved, Files.readAllBytes(hold));
        assertEquals(List.of("failed plains", "next plains"), Files.readAllLines(queue));
    }

    @Test void dimensionFailureHoldsUntouchedRemainingQueueWithoutInventingARetry() throws Exception {
        Path queue = dir.resolve("queue"), hold = dir.resolve("hold");
        Files.write(queue, List.of("next plains", "Briarwood_House_TIER_2_ plains"));
        NaturalSession.holdFailedQueuedTake(queue, hold, null);
        assertFalse(Files.exists(queue));
        assertEquals(List.of("next plains", "Briarwood_House_TIER_2_ plains"), Files.readAllLines(hold));
    }
}
