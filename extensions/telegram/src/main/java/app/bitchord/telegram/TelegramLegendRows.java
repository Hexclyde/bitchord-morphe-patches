package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;

/** Query snapshots used only by Telegram-backed format legends. */
final class TelegramLegendRows {
    static final class Snapshot {
        final Object epoch;
        final List<JSONObject> rows;

        Snapshot(Object epoch, List<JSONObject> rows) {
            this.epoch = epoch;
            this.rows = rows;
        }
    }

    private Object epoch = new Object();
    private String query = "";
    private List<JSONObject> rows = Collections.emptyList();

    synchronized Object begin(String wanted) {
        if (wanted.equalsIgnoreCase(query)) return null;
        query = wanted;
        return epoch = new Object();
    }

    synchronized boolean publish(Object expected, String wanted, List<JSONObject> found) {
        if (expected != epoch || !wanted.equals(query)) return false;
        rows = Collections.unmodifiableList(new ArrayList<>(found));
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(epoch, rows);
    }

    synchronized boolean commit(Snapshot snapshot, Runnable action) {
        if (snapshot.epoch != epoch) return false;
        action.run();
        return true;
    }

    synchronized void forget(long chat, Set<Long> messages) {
        ArrayList<JSONObject> kept = new ArrayList<>();
        for (JSONObject row : rows)
            if (row.optLong("chat") != chat
                    || messages != null && !messages.contains(row.optLong("message")))
                kept.add(row);
        rows = Collections.unmodifiableList(kept);
        query = "";
        epoch = new Object();
    }
}
