package org.akj.reviewer.github;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeenPrTrackerTest {

    private SeenPrTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SeenPrTracker();
    }

    @Test
    void testSeenPrFlow() {
        String repo = "owner/repo";
        int prNum = 42;
        String sha1 = "abcdef123456";
        String sha2 = "7890abcdef12";

        // Initially unseen
        assertFalse(tracker.hasBeenSeen(repo, prNum, sha1));
        assertEquals(0, tracker.size());

        // Mark seen
        tracker.markSeen(repo, prNum, sha1);
        assertTrue(tracker.hasBeenSeen(repo, prNum, sha1));
        assertFalse(tracker.hasBeenSeen(repo, prNum, sha2)); // different SHA
        assertFalse(tracker.hasBeenSeen(repo, 99, sha1));     // different PR
        assertEquals(1, tracker.size());

        // Mark different SHA
        tracker.markSeen(repo, prNum, sha2);
        assertTrue(tracker.hasBeenSeen(repo, prNum, sha2));
        assertFalse(tracker.hasBeenSeen(repo, prNum, sha1)); // old SHA is replaced
        assertEquals(1, tracker.size());

        // Unmark
        tracker.unmark(repo, prNum);
        assertFalse(tracker.hasBeenSeen(repo, prNum, sha2));
        assertEquals(0, tracker.size());
    }
}
