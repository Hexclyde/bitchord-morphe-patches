package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class PlaylistSyncPlanTest {
    PlaylistSyncPlan.Snapshot snapshot(String account, String... ids) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String id : ids) entries.put(id, id + "-entry");
        return new PlaylistSyncPlan.Snapshot(account, "rev-1", entries, true);
    }

    Map<String, PlaylistSyncPlan.Snapshot> peers(String[] a, String[] b) {
        Map<String, PlaylistSyncPlan.Snapshot> peers = new LinkedHashMap<>();
        peers.put("a", snapshot("alice", a));
        peers.put("b", snapshot("bob", b));
        return peers;
    }

    PlaylistSyncPlan.Baseline baseline() {
        Map<String, PlaylistSyncPlan.Snapshot> peers =
                peers(new String[] {"one", "two"}, new String[] {"one", "two"});
        return PlaylistSyncPlan.plan(null, peers, false, 100, 0).verified(peers);
    }

    @Test
    public void aRepairedAbsenceCannotCountAsTheFirstReadOfALaterDeletion() {
        PlaylistSyncPlan.Baseline base = baseline();
        Map<String, PlaylistSyncPlan.Snapshot> missing =
                peers(new String[] {"one"}, new String[] {"one", "two"});
        PlaylistSyncPlan repair = PlaylistSyncPlan.plan(base, missing, false, 100, 0);
        PlaylistSyncPlan.Baseline repaired =
                repair.verified(peers(new String[] {"one", "two"}, new String[] {"one", "two"}));
        assertTrue(repaired.missing.get("a").isEmpty());
        assertTrue(PlaylistSyncPlan.plan(repaired, missing, true, 100, 10).awaitingConfirmation);
    }

    @Test
    public void firstPassUnionsPlaylistsWithoutDeletingAnything() {
        PlaylistSyncPlan plan =
                PlaylistSyncPlan.plan(
                        null, peers(new String[] {"one"}, new String[] {"two"}), false, 100, 0);
        assertEquals(Arrays.asList("two"), plan.changes.get("a").add);
        assertTrue(plan.changes.get("b").remove.isEmpty());
    }

    @Test
    public void deletionRequiresOptInAndTwoCompleteReads() {
        PlaylistSyncPlan.Baseline base = baseline();
        Map<String, PlaylistSyncPlan.Snapshot> peers =
                peers(new String[] {"one"}, new String[] {"one", "two"});
        PlaylistSyncPlan first = PlaylistSyncPlan.plan(base, peers, true, 100, 10);
        assertTrue(first.awaitingConfirmation);
        assertTrue(first.changes.get("a").add.isEmpty());
        base = first.observedBaseline(base);
        PlaylistSyncPlan second = PlaylistSyncPlan.plan(base, peers, true, 100, 10);
        assertEquals(Arrays.asList("two"), second.changes.get("b").remove);
        assertTrue(
                PlaylistSyncPlan.plan(base, peers, false, 100, 0)
                        .changes
                        .get("b")
                        .remove
                        .isEmpty());
    }

    @Test
    public void reAddedOccurrenceWinsOverConfirmedRemoval() {
        PlaylistSyncPlan.Baseline base = baseline();
        base.missing.put("a", new HashSet<>(Arrays.asList("two")));
        Map<String, PlaylistSyncPlan.Snapshot> peers =
                peers(new String[] {"one"}, new String[] {"one", "two"});
        peers.get("b").entries.put("two", "new-entry");
        assertTrue(PlaylistSyncPlan.plan(base, peers, true, 100, 10).desired.contains("two"));
    }

    @Test
    public void failedReadsAccountChangesAndExcessiveWritesBlockTheWholePlan() {
        PlaylistSyncPlan.Baseline base = baseline();
        Map<String, PlaylistSyncPlan.Snapshot> peers =
                peers(new String[] {"one", "two"}, new String[] {"one", "two"});
        peers.put(
                "a", new PlaylistSyncPlan.Snapshot("alice", "rev", Collections.emptyMap(), false));
        assertThrows(
                IllegalStateException.class,
                () -> PlaylistSyncPlan.plan(base, peers, true, 100, 10));
        peers.put("a", snapshot("other", "one", "two"));
        assertThrows(
                IllegalStateException.class,
                () -> PlaylistSyncPlan.plan(base, peers, false, 100, 0));
        assertThrows(
                IllegalStateException.class,
                () ->
                        PlaylistSyncPlan.plan(
                                null,
                                peers(new String[] {"one"}, new String[] {"two"}),
                                false,
                                0,
                                0));
    }

    @Test
    public void baselineAdvancesOnlyAfterAllWritesAreVerified() {
        PlaylistSyncPlan plan =
                PlaylistSyncPlan.plan(
                        null, peers(new String[] {"one"}, new String[] {"two"}), false, 100, 0);
        assertThrows(
                IllegalStateException.class,
                () -> plan.verified(peers(new String[] {"one"}, new String[] {"two"})));
        PlaylistSyncPlan.Baseline done =
                plan.verified(peers(new String[] {"one", "two"}, new String[] {"one", "two"}));
        assertEquals(2, done.canonical.size());
    }
}
