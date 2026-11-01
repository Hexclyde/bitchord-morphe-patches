package app.bitchord.telegram;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Loads each owner on its own, and re-checks every item immediately before removing it. */
final class StorageSenseEngine {
    interface ActiveSource {
        StorageSense.Active capture();
    }

    interface Clock {
        long now();
    }

    enum State {
        READY,
        EMPTY,
        ERROR,
        UNAVAILABLE
    }

    static final class Section {
        final StorageSense.Category category;
        final State state;
        final List<StorageSense.Item> items;
        final long bytes;
        final String message;
        final boolean truncated;

        Section(
                StorageSense.Category category,
                State state,
                List<StorageSense.Item> items,
                String message,
                boolean truncated) {
            this.category = category;
            this.state = state;
            this.items = items;
            this.message = message;
            this.truncated = truncated;
            long total = 0;
            for (StorageSense.Item item : items) total += Math.max(0, item.bytes);
            bytes = total;
        }
    }

    static final class Snapshot {
        final Map<StorageSense.Category, Section> sections =
                new EnumMap<>(StorageSense.Category.class);
        boolean cancelled;

        List<StorageSense.Item> items() {
            List<StorageSense.Item> all = new ArrayList<>();
            for (Section section : sections.values()) all.addAll(section.items);
            return all;
        }
    }

    static final class Outcome {
        int removed, blocked, failed;
        long bytes;
        boolean cancelled;
        String firstError = "";
        StorageSense.Block firstBlock = StorageSense.Block.NONE;
    }

    private final List<StorageSense.Owner> owners;
    private final ActiveSource active;
    final Clock clock;

    StorageSenseEngine(List<StorageSense.Owner> owners, ActiveSource active, Clock clock) {
        this.owners = owners;
        this.active = active;
        this.clock = clock;
    }

    Snapshot load(StorageSense.Cancel cancel) {
        Snapshot snapshot = new Snapshot();
        StorageSense.Active now = active.capture();
        for (StorageSense.Owner owner : owners) {
            if (cancel.cancelled()) {
                snapshot.cancelled = true;
                return snapshot;
            }
            StorageSense.Listing listing = null;
            State failure = State.READY;
            String message = "";
            try {
                listing = owner.list(now, cancel);
            } catch (StorageSense.Unavailable unavailable) {
                failure = State.UNAVAILABLE;
                message = String.valueOf(unavailable.getMessage());
            } catch (Exception | LinkageError error) {
                failure = State.ERROR;
                message = error.getMessage() == null ? "" : error.getMessage();
            }
            for (StorageSense.Category category : owner.categories()) {
                if (listing == null) {
                    snapshot.sections.put(
                            category,
                            new Section(category, failure, new ArrayList<>(), message, false));
                    continue;
                }
                List<StorageSense.Item> mine = new ArrayList<>();
                for (StorageSense.Item item : listing.items)
                    if (item.category == category) mine.add(item);
                snapshot.sections.put(
                        category,
                        new Section(
                                category,
                                mine.isEmpty() ? State.EMPTY : State.READY,
                                mine,
                                "",
                                listing.truncated));
            }
        }
        snapshot.cancelled = cancel.cancelled();
        return snapshot;
    }

    private StorageSense.Owner ownerOf(StorageSense.Item item) {
        for (StorageSense.Owner owner : owners)
            for (StorageSense.Category category : owner.categories())
                if (category == item.category) return owner;
        return null;
    }

    /**
     * Removes what's still safe. Stops between items when cancelled, never in the middle of one.
     */
    Outcome remove(List<StorageSense.Item> items, StorageSense.Cancel cancel) {
        Outcome outcome = new Outcome();
        for (StorageSense.Item item : items) {
            if (cancel.cancelled()) {
                outcome.cancelled = true;
                return outcome;
            }
            StorageSense.Owner owner = ownerOf(item);
            StorageSense.Block block;
            try {
                block =
                        owner == null
                                ? StorageSense.Block.READ_ONLY
                                : item.effect == StorageSense.Effect.NONE
                                        ? StorageSense.Block.READ_ONLY
                                        : owner.recheck(item, active.capture());
            } catch (Exception | LinkageError error) {
                block = StorageSense.Block.STATE_UNKNOWN;
            }
            if (block != StorageSense.Block.NONE) {
                outcome.blocked++;
                if (outcome.firstBlock == StorageSense.Block.NONE) outcome.firstBlock = block;
                continue;
            }
            try {
                owner.remove(item);
                outcome.removed++;
                outcome.bytes += item.bytes;
            } catch (StorageSense.Refused refused) {
                outcome.blocked++;
                if (outcome.firstBlock == StorageSense.Block.NONE)
                    outcome.firstBlock = refused.reason;
            } catch (Exception | LinkageError error) {
                outcome.failed++;
                if (outcome.firstError.isEmpty() && error.getMessage() != null)
                    outcome.firstError = error.getMessage();
            }
        }
        return outcome;
    }
}
