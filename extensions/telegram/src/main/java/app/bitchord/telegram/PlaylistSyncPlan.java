package app.bitchord.telegram;

import java.util.*;

/** Pure reconciliation; callers must read complete snapshots and verify every applied write. */
final class PlaylistSyncPlan {
    static final class Snapshot {
        final String account, revision;
        final LinkedHashMap<String, String> entries;
        final boolean complete;

        Snapshot(String account, String revision, Map<String, String> entries, boolean complete) {
            this.account = account;
            this.revision = revision;
            this.entries = new LinkedHashMap<>(entries);
            this.complete = complete;
        }
    }

    static final class Baseline {
        final LinkedHashSet<String> canonical = new LinkedHashSet<>();
        final Map<String, String> accounts = new LinkedHashMap<>();
        final Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        final Map<String, Set<String>> missing = new LinkedHashMap<>();
    }

    static final class Change {
        final List<String> add = new ArrayList<>(), remove = new ArrayList<>();
        final String revision;

        Change(String revision) {
            this.revision = revision;
        }
    }

    final Map<String, Change> changes = new LinkedHashMap<>();
    final LinkedHashSet<String> desired = new LinkedHashSet<>();
    final Map<String, Set<String>> observedMissing = new LinkedHashMap<>();
    final Map<String, Snapshot> observed;
    boolean awaitingConfirmation;

    private PlaylistSyncPlan(Map<String, Snapshot> observed) {
        this.observed = new LinkedHashMap<>(observed);
    }

    static PlaylistSyncPlan plan(
            Baseline baseline,
            Map<String, Snapshot> snapshots,
            boolean removals,
            int maxAdds,
            int maxRemovals) {
        if (snapshots.size() < 2 || maxAdds < 0 || maxRemovals < 0)
            throw new IllegalArgumentException("Invalid sync configuration");
        PlaylistSyncPlan plan = new PlaylistSyncPlan(snapshots);
        boolean established = baseline != null && !baseline.accounts.isEmpty();
        if (established && !baseline.accounts.keySet().equals(snapshots.keySet()))
            throw new IllegalStateException("Connections changed; establish a new baseline");
        LinkedHashSet<String> additions = new LinkedHashSet<>(), confirmed = new LinkedHashSet<>();
        if (established) plan.desired.addAll(baseline.canonical);
        for (Map.Entry<String, Snapshot> peer : snapshots.entrySet()) {
            Snapshot current = peer.getValue();
            if (!current.complete) throw new IllegalStateException("A playlist read is incomplete");
            if (current.account == null || current.account.isEmpty() || current.revision == null)
                throw new IllegalStateException("Connection identity is unavailable");
            if (established && !current.account.equals(baseline.accounts.get(peer.getKey())))
                throw new IllegalStateException("A connected account changed");
            Map<String, String> previous = established ? baseline.entries.get(peer.getKey()) : null;
            if (established && previous == null)
                throw new IllegalStateException("Sync baseline is incomplete");
            if (previous != null
                    && previous.size() >= 10
                    && current.entries.size() < previous.size() * .6)
                throw new IllegalStateException("Playlist read collapsed; review before syncing");
            Set<String> absent = new LinkedHashSet<>();
            if (established)
                for (String id : baseline.canonical)
                    if (!current.entries.containsKey(id)) absent.add(id);
            plan.observedMissing.put(peer.getKey(), absent);
            for (String id : current.entries.keySet())
                if (!established
                        || !baseline.canonical.contains(id)
                        || previous.containsKey(id)
                                && !Objects.equals(previous.get(id), current.entries.get(id)))
                    additions.add(id);
            Set<String> earlier = established ? baseline.missing.get(peer.getKey()) : null;
            if (removals && earlier != null)
                for (String id : absent) if (earlier.contains(id)) confirmed.add(id);
            if (!established) plan.desired.addAll(current.entries.keySet());
        }
        confirmed.removeAll(additions);
        plan.desired.removeAll(confirmed);
        plan.desired.addAll(additions);
        if (removals && established)
            for (Set<String> absent : plan.observedMissing.values()) {
                Set<String> pending = new HashSet<>(absent);
                pending.removeAll(confirmed);
                pending.removeAll(additions);
                if (!pending.isEmpty()) plan.awaitingConfirmation = true;
            }
        for (Map.Entry<String, Snapshot> peer : snapshots.entrySet()) {
            Change change = new Change(peer.getValue().revision);
            if (!plan.awaitingConfirmation)
                for (String id : plan.desired)
                    if (!peer.getValue().entries.containsKey(id)) change.add.add(id);
            if (removals && !plan.awaitingConfirmation)
                for (String id : peer.getValue().entries.keySet())
                    if (!plan.desired.contains(id)) change.remove.add(id);
            if (change.add.size() > maxAdds || change.remove.size() > maxRemovals)
                throw new IllegalStateException("Change limit exceeded; review the sync plan");
            plan.changes.put(peer.getKey(), change);
        }
        return plan;
    }

    Baseline observedBaseline(Baseline previous) {
        if (previous == null) throw new IllegalStateException("No established baseline");
        Baseline next = new Baseline();
        next.canonical.addAll(previous.canonical);
        next.accounts.putAll(previous.accounts);
        for (Map.Entry<String, Map<String, String>> peer : previous.entries.entrySet())
            next.entries.put(peer.getKey(), new LinkedHashMap<>(peer.getValue()));
        for (Map.Entry<String, Set<String>> peer : observedMissing.entrySet())
            next.missing.put(peer.getKey(), new LinkedHashSet<>(peer.getValue()));
        return next;
    }

    Baseline verified(Map<String, Snapshot> after) {
        if (awaitingConfirmation)
            throw new IllegalStateException("A second complete read must confirm removals");
        if (!after.keySet().equals(observed.keySet()))
            throw new IllegalStateException("Not all connections were verified");
        Baseline next = new Baseline();
        next.canonical.addAll(desired);
        for (Map.Entry<String, Snapshot> peer : after.entrySet()) {
            Snapshot value = peer.getValue();
            if (!value.complete
                    || !Objects.equals(value.account, observed.get(peer.getKey()).account)
                    || !value.entries.keySet().equals(desired))
                throw new IllegalStateException("Written playlists could not be verified");
            next.accounts.put(peer.getKey(), value.account);
            next.entries.put(peer.getKey(), new LinkedHashMap<>(value.entries));
            next.missing.put(peer.getKey(), new LinkedHashSet<>());
        }
        return next;
    }
}
