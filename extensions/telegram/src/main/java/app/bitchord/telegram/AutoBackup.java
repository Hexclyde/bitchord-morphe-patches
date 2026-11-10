package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backups posted by themselves to a private channel the account owns. This class holds the
 * decisions and the saved state; Telegram and the phone are reached through {@link Host}, so the
 * rules can be checked without either.
 *
 * <p>A backup is a transaction: the file is staged and the intent saved before anything is sent,
 * the send is only counted as done once Telegram holds the posted file, and the staging file stays
 * until then. A send that may or may not have happened is looked for by its token. It is sent again
 * only after Telegram reports that it failed; time passing settles nothing. Nothing here holds the
 * state while it waits for Telegram.
 */
final class AutoBackup {
    enum Contents {
        INDEX,
        SETTINGS,
        BOTH;

        static Contents parse(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (RuntimeException unknown) {
                return BOTH;
            }
        }
    }

    /** The start of the caption of every automatic backup; retention only touches these. */
    static final String MARKER = "BitChord automatic backup";

    static final String FILE_PREFIX = "bitchord-telegram-backup-";

    static final long MIN_RETRY_MILLIS = 5 * 60_000L, MAX_RETRY_MILLIS = 6 * 3600_000L;
    static final long BUSY_MILLIS = 60_000L, PENDING_MILLIS = 2 * 60_000L;
    static final long AWAIT_SLICE_MILLIS = 30_000L, AWAIT_TOTAL_MILLIS = 8 * 60_000L;

    static final long[] PRESET_HOURS = {1, 3, 6, 12, 24};
    static final long MAX_HOURS = 24 * 365L;
    static final int DEFAULT_KEEP = 5, MAX_KEEP = 100;

    interface Clock {
        long millis();
    }

    /** Telegram asked for a pause before the next request. */
    static final class FloodWait extends IOException {
        final int seconds;

        FloodWait(int seconds) {
            super("Telegram asked to wait " + seconds + " seconds");
            this.seconds = Math.max(1, seconds);
        }
    }

    /** The host can't tell whether the message was queued; it is looked for, not sent again. */
    static final class Uncertain extends IOException {
        Uncertain(String message) {
            super(message);
        }
    }

    /** A posted file as Telegram holds it. */
    static final class Sent {
        long chat, id, size;
        int date;
        boolean outgoing, pending, failed;
        String name = "", caption = "", reason = "";
    }

    interface Store {
        JSONObject read() throws Exception;

        void write(JSONObject state) throws Exception;
    }

    interface Host {
        /** Signed in and connected. */
        boolean ready();

        /**
         * True while nothing the listener is waiting for (playback, preview, foreground search)
         * needs the network. A backup the user asked for waits for the same quiet.
         */
        boolean allowed(boolean manual);

        /** A stand-in for the signed-in account. */
        String account() throws Exception;

        /** Whether the chat is a private channel this account created. */
        boolean ownedPrivateChannel(long chat) throws Exception;

        /** Writes a checked backup file in the app's cache. */
        File stage(Contents contents) throws Exception;

        /**
         * Hands the file to Telegram; returns the temporary message id. An exception other than
         * {@link Uncertain} means nothing was queued.
         */
        long send(long chat, File file, String caption) throws Exception;

        /**
         * Waits up to {@code millis} for Telegram to finish posting the message; null when it
         * hasn't. A result that arrived before this call is still returned.
         */
        Sent await(long chat, long temporaryId, long millis) throws Exception;

        /** The message with this token in its caption, pending or posted, or null. */
        Sent find(long chat, String token) throws Exception;

        /** Posted documents of this account in the chat, newest first. */
        List<Sent> documents(long chat) throws Exception;

        void delete(long chat, long[] ids) throws Exception;

        /** Removes a post Telegram reported as failed. */
        void discard(long chat, long id) throws Exception;
    }

    private final Host host;
    private final Store store;
    private final Clock clock;
    private JSONObject state = new JSONObject();

    AutoBackup(Host host, Store store, Clock clock) throws Exception {
        this.host = host;
        this.store = store;
        this.clock = clock;
        JSONObject saved = store.read();
        if (saved != null) state = saved;
    }

    // ── Settings ────────────────────────────────────────────────────────────

    synchronized boolean enabled() {
        return state.optBoolean("enabled");
    }

    synchronized long target() {
        return state.optLong("target");
    }

    synchronized String targetTitle() {
        return state.optString("targetTitle");
    }

    synchronized Contents contents() {
        return Contents.parse(state.optString("contents", "BOTH"));
    }

    synchronized long intervalHours() {
        return state.optLong("hours", 24);
    }

    /** How many automatic backups stay in the channel; 0 keeps all of them. */
    synchronized int keep() {
        return state.optInt("keep", DEFAULT_KEEP);
    }

    synchronized long lastAttempt() {
        return state.optLong("lastAttempt");
    }

    synchronized long lastSuccess() {
        return state.optLong("lastSuccess");
    }

    synchronized String error() {
        return state.optString("error");
    }

    /** When the next attempt may start, or 0 when none is planned. */
    synchronized long nextAt() {
        if ((!enabled() && !unresolved()) || (target() == 0 && !unresolved())) return 0;
        return Math.max(state.optLong("nextDue"), state.optLong("retryAt"));
    }

    synchronized boolean pending() {
        return state.optJSONObject("tx") != null;
    }

    /** A whole number of hours from 1 to a year; anything else is refused. */
    static long parseHours(String text) {
        try {
            long hours = Long.parseLong(text == null ? "" : text.trim());
            if (hours < 1 || hours > MAX_HOURS)
                throw new IllegalArgumentException(
                        "Enter a whole number of hours from 1 to " + MAX_HOURS + ".");
            return hours;
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "Enter a whole number of hours from 1 to " + MAX_HOURS + ".");
        }
    }

    synchronized void setEnabled(boolean on) throws Exception {
        if (on == enabled()) return;
        state.put("enabled", on);
        if (on) {
            state.put("nextDue", clock.millis());
            state.put("retryAt", 0);
            state.put("failures", 0);
        }
        save();
    }

    /**
     * The channel backups go to. A different channel drops what was staged for the old one; a send
     * that may already have happened is left where Telegram has it and never repeated.
     */
    void setTarget(long chat, String title) throws Exception {
        // Asked of Telegram before the state is taken, so settings stay responsive.
        String account = chat == 0 ? "" : accountOrEmpty();
        synchronized (this) {
            if (chat != target()) abandon();
            state.put("target", chat).put("targetTitle", title == null ? "" : title);
            state.put("account", account);
            state.put("error", "").put("retryAt", 0).put("failures", 0);
            if (enabled()) state.put("nextDue", clock.millis());
            save();
        }
    }

    synchronized void setContents(Contents contents) throws Exception {
        if (contents == contents()) return;
        abandon();
        state.put("contents", contents.name());
        state.put("error", "");
        save();
    }

    synchronized void setIntervalHours(long hours) throws Exception {
        if (hours < 1 || hours > MAX_HOURS) throw new IllegalArgumentException("Invalid interval");
        long last = lastSuccess();
        state.put("hours", hours);
        // The new interval counts from the last backup, not from now.
        if (enabled() && last > 0)
            state.put("nextDue", Math.max(clock.millis(), last + hours * 3600_000L));
        save();
    }

    synchronized void setKeep(int count) throws Exception {
        if (count < 0 || count > MAX_KEEP) throw new IllegalArgumentException("Invalid count");
        state.put("keep", count);
        save();
    }

    private String accountOrEmpty() {
        try {
            return host.account();
        } catch (Exception unavailable) {
            return "";
        }
    }

    /**
     * Drops a staged file that was never handed to Telegram. An attempt that may already be on its
     * way is kept and settled against the chat it went to; nothing is deleted on a timer.
     */
    private void abandon() throws Exception {
        JSONObject tx = state.optJSONObject("tx");
        if (tx == null || !tx.optString("state").equals("staged")) return;
        state.remove("tx");
        String path = tx.optString("file");
        if (!path.isEmpty()) new File(path).delete();
        save();
    }

    // ── Running ─────────────────────────────────────────────────────────────

    /** What a run leaves for the scheduler. */
    static final class Outcome {
        /** Milliseconds until the next run, or -1 when none is needed. */
        final long delayMillis;

        Outcome(long delayMillis) {
            this.delayMillis = delayMillis;
        }
    }

    /** A copy of the attempt in progress, safe to use while the saved state changes. */
    private static final class Tx {
        String token = "", state = "", file = "";
        long size, target, temp;
        Contents contents = Contents.BOTH;
    }

    private boolean running;

    /** How long until the scheduler should run again, from the saved state. */
    synchronized long delayMillis() {
        if (unresolved() && !enabled()) return PENDING_MILLIS;
        long at = nextAt();
        return at == 0 ? -1 : Math.max(0, at - clock.millis());
    }

    /**
     * A backup the user asked for now. It ignores the interval and an earlier failure's wait, but
     * not a pause Telegram asked for, and it still yields to playback, previews and searches.
     */
    Outcome runNow(AtomicBoolean stop) throws Exception {
        return run(stop, true);
    }

    Outcome run(AtomicBoolean stop) throws Exception {
        return run(stop, false);
    }

    private Outcome run(AtomicBoolean stop, boolean manual) throws Exception {
        synchronized (this) {
            if (running) return new Outcome(BUSY_MILLIS);
            Outcome early = admit(manual);
            if (early != null) return early;
            running = true;
        }
        try {
            return execute(stop, manual);
        } finally {
            synchronized (this) {
                running = false;
            }
        }
    }

    /** Null when a run may go ahead; otherwise when to look again. Only reads the saved state. */
    private Outcome admit(boolean manual) {
        boolean waiting = unresolved();
        if (!enabled() && !manual && !waiting) return new Outcome(-1);
        if (target() == 0 && !waiting) return new Outcome(-1);
        long now = clock.millis();
        long flood = state.optLong("floodUntil");
        if (now < flood) return new Outcome(flood - now);
        long retry = state.optLong("retryAt");
        if (!manual && now < retry) return new Outcome(retry - now);
        long due = state.optLong("nextDue");
        if (!manual && !pendingTx() && now < due) return new Outcome(due - now);
        return null;
    }

    private Outcome execute(AtomicBoolean stop, boolean manual) throws Exception {
        try {
            if (!host.ready()) return new Outcome(BUSY_MILLIS);
            if (stop.get() || !host.allowed(manual)) return new Outcome(BUSY_MILLIS);
            String account;
            synchronized (this) {
                account = state.optString("account");
            }
            if (!account.isEmpty() && !account.equals(host.account()))
                return failed(
                        new IOException(
                                "This is a different Telegram account. Pick the backup channel"
                                        + " again."),
                        MAX_RETRY_MILLIS);
            Tx tx = tx();
            if (tx == null) {
                long chat = target();
                if (!host.ownedPrivateChannel(chat))
                    return failed(
                            new IOException(
                                    "The backup channel must be a private channel you created."),
                            MAX_RETRY_MILLIS);
                // Staging reads the whole index, so it waits for the same quiet as a send.
                if (stop.get() || !host.allowed(manual)) return new Outcome(BUSY_MILLIS);
                tx = begin();
                if (tx == null) return new Outcome(0);
            }
            return drive(tx, stop, manual);
        } catch (FloodWait flood) {
            long wait = flood.seconds * 1000L + 1000;
            synchronized (this) {
                state.put("floodUntil", clock.millis() + wait)
                        .put("retryAt", clock.millis() + wait)
                        .put("error", safe(flood.getMessage()));
                save();
            }
            return new Outcome(wait);
        } catch (IOException | RuntimeException e) {
            return failed(e, 0);
        }
    }

    private synchronized boolean pendingTx() {
        return state.optJSONObject("tx") != null;
    }

    /** An attempt that was handed to Telegram and hasn't been settled yet. */
    synchronized boolean unresolved() {
        JSONObject tx = state.optJSONObject("tx");
        return tx != null && tx.optString("state").equals("sending");
    }

    private synchronized Tx tx() {
        JSONObject t = state.optJSONObject("tx");
        if (t == null) return null;
        Tx x = new Tx();
        x.token = t.optString("token");
        x.state = t.optString("state");
        x.file = t.optString("file");
        x.size = t.optLong("size");
        x.target = t.optLong("target");
        x.temp = t.optLong("temp");
        x.contents = Contents.parse(t.optString("contents"));
        return x;
    }

    /** Changes the attempt's step if it is still the one this run holds. */
    private synchronized boolean edit(String token, String step, long temp) throws Exception {
        JSONObject t = state.optJSONObject("tx");
        if (t == null || !t.optString("token").equals(token)) return false;
        t.put("state", step).put("temp", temp);
        save();
        return true;
    }

    /** Stages the file without holding the state; null when the settings changed meanwhile. */
    private Tx begin() throws Exception {
        Contents contents;
        long chat;
        synchronized (this) {
            contents = contents();
            chat = target();
            state.put("lastAttempt", clock.millis());
            save();
        }
        File file = host.stage(contents);
        long size = file.length();
        if (size <= 0) {
            file.delete();
            throw new IOException("The backup came out empty. Nothing was sent.");
        }
        synchronized (this) {
            if (state.optJSONObject("tx") != null || chat != target() || contents != contents()) {
                file.delete();
                return null;
            }
            JSONObject t =
                    new JSONObject()
                            .put("token", newToken())
                            .put("state", "staged")
                            .put("file", file.getAbsolutePath())
                            .put("size", size)
                            .put("target", chat)
                            .put("contents", contents.name())
                            .put("temp", 0);
            state.put("tx", t);
            save();
        }
        return tx();
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Outcome drive(Tx tx, AtomicBoolean stop, boolean manual) throws Exception {
        long chat = tx.target;
        if (tx.state.equals("staged")) {
            File file = new File(tx.file);
            boolean stale;
            synchronized (this) {
                stale =
                        !file.isFile()
                                || file.length() != tx.size
                                || chat != target()
                                || tx.contents != contents()
                                || (!enabled() && !manual);
                if (stale) {
                    JSONObject t = state.optJSONObject("tx");
                    if (t != null && t.optString("token").equals(tx.token)) {
                        state.remove("tx");
                        save();
                    }
                }
            }
            if (stale) {
                // Never handed to Telegram, so nothing can be on its way.
                file.delete();
                return new Outcome(0);
            }
            if (stop.get() || !host.allowed(manual)) return new Outcome(BUSY_MILLIS);
            // Saved before the send: if this process dies in it, the token finds the message.
            if (!edit(tx.token, "sending", 0)) return new Outcome(0);
            long temp;
            try {
                temp = host.send(chat, file, MARKER + " " + tx.token);
            } catch (Uncertain unknown) {
                throw unknown;
            } catch (IOException | RuntimeException refused) {
                // The send was refused, so no message exists and the staged file can be sent later.
                edit(tx.token, "staged", 0);
                throw refused;
            }
            edit(tx.token, "sending", temp);
            tx.temp = temp;
        }
        return settle(tx, stop, manual);
    }

    /**
     * Waits for Telegram to say what became of the message. It only moves on when Telegram has
     * posted it or reported that it failed; time passing alone settles nothing.
     */
    private Outcome settle(Tx tx, AtomicBoolean stop, boolean manual) throws Exception {
        long chat = tx.target;
        Sent posted = host.find(chat, tx.token);
        for (int slice = 0; slice < AWAIT_TOTAL_MILLIS / AWAIT_SLICE_MILLIS; slice++) {
            if (posted != null && posted.failed) return refused(tx, posted);
            if (posted != null && !posted.pending) return confirm(tx, posted);
            if (stop.get() || !host.allowed(manual)) return new Outcome(BUSY_MILLIS);
            if (tx.temp == 0) break;
            Sent done = host.await(chat, tx.temp, AWAIT_SLICE_MILLIS);
            if (done != null && done.failed) return refused(tx, done);
            if (done != null && !done.pending) return confirm(tx, done);
            posted = host.find(chat, tx.token);
        }
        if (posted != null && posted.failed) return refused(tx, posted);
        if (posted != null && !posted.pending) return confirm(tx, posted);
        // Not posted and not reported failed: it may still be on its way, so it is not sent again.
        int waits;
        synchronized (this) {
            waits = state.optInt("waits") + 1;
            state.put("waits", waits)
                    .put("error", "Waiting for Telegram to confirm the last backup.");
            save();
        }
        return new Outcome(Math.min(MAX_RETRY_MILLIS, PENDING_MILLIS << Math.min(6, waits - 1)));
    }

    /** Telegram reported that it could not post the message; it is safe to send a new one. */
    private Outcome refused(Tx tx, Sent failure) throws Exception {
        try {
            if (failure.id != 0) host.discard(tx.target, failure.id);
        } catch (Exception leftBehind) {
            HostLog.d("Automatic backup couldn't remove a failed post");
        }
        synchronized (this) {
            JSONObject t = state.optJSONObject("tx");
            if (t != null && t.optString("token").equals(tx.token)) {
                // A new token, so the failed post can't be mistaken for the next one.
                t.put("token", newToken()).put("state", "staged").put("temp", 0);
                save();
            }
        }
        return failed(
                new IOException(
                        failure.reason.isEmpty()
                                ? "Telegram couldn't post the backup."
                                : failure.reason),
                0);
    }

    private Outcome confirm(Tx tx, Sent posted) throws Exception {
        long chat = tx.target;
        boolean same =
                posted.chat == chat
                        && posted.outgoing
                        && posted.name.startsWith(FILE_PREFIX)
                        && posted.caption.equals(MARKER + " " + tx.token)
                        && posted.size == tx.size;
        if (!same) {
            // Whatever Telegram holds is not the file that was staged. The staged file stays.
            return failed(new IOException("Telegram holds a different file than the one sent."), 0);
        }
        long now = clock.millis();
        synchronized (this) {
            JSONObject t = state.optJSONObject("tx");
            if (t == null || !t.optString("token").equals(tx.token)) return new Outcome(0);
            state.remove("tx");
            state.put("lastSuccess", now)
                    .put("error", "")
                    .put("failures", 0)
                    .put("waits", 0)
                    .put("retryAt", 0)
                    .put("floodUntil", 0)
                    .put("nextDue", now + intervalHours() * 3600_000L);
            save();
        }
        new File(tx.file).delete();
        if (chat == target()) trim(chat, posted);
        return new Outcome(delayMillis());
    }

    /**
     * Removes older automatic backups beyond {@link #keep()}, only after the new one is confirmed,
     * and only messages this account posted with this app's caption and file name.
     */
    private void trim(long chat, Sent confirmed) {
        int keep = keep();
        if (keep == 0) return;
        try {
            List<Sent> mine = new ArrayList<>();
            boolean listed = false;
            for (Sent s : host.documents(chat)) {
                if (s.id == confirmed.id) listed = true;
                if (s.chat == chat && s.outgoing && !s.pending && !s.failed && ours(s)) mine.add(s);
            }
            if (!listed) return;
            Collections.sort(
                    mine,
                    (a, b) ->
                            a.date != b.date
                                    ? Integer.compare(b.date, a.date)
                                    : Long.compare(b.id, a.id));
            if (mine.size() <= keep) return;
            long[] old = new long[mine.size() - keep];
            for (int i = 0; i < old.length; i++) old[i] = mine.get(keep + i).id;
            host.delete(chat, old);
        } catch (Exception e) {
            // Keeping extra copies is the safe failure; the next backup tries again.
            HostLog.d(
                    "Automatic backup couldn't remove old backups: "
                            + e.getClass().getSimpleName());
        }
    }

    private static final java.util.regex.Pattern OUR_NAME =
            java.util.regex.Pattern.compile(
                    "bitchord-telegram-backup-[0-9]{8}-[0-9a-f-]{1,40}\\.gz");
    private static final java.util.regex.Pattern OUR_CAPTION =
            java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(MARKER) + " [0-9a-f]{12}");

    /** Whether a post has exactly the file name and caption this app gives its backups. */
    static boolean ours(Sent s) {
        return OUR_NAME.matcher(s.name).matches() && OUR_CAPTION.matcher(s.caption).matches();
    }

    private synchronized Outcome failed(Exception e, long minimum) throws Exception {
        int failures = state.optInt("failures") + 1;
        long wait =
                Math.max(
                        minimum,
                        Math.min(MAX_RETRY_MILLIS, MIN_RETRY_MILLIS << Math.min(6, failures - 1)));
        state.put("failures", failures)
                .put("error", safe(e.getMessage()))
                .put("retryAt", clock.millis() + wait);
        save();
        return new Outcome(wait);
    }

    /** An error as shown: no long tokens, no file paths, no more than a sentence or two. */
    static String safe(String message) {
        String text = message == null || message.isEmpty() ? "The backup didn't finish." : message;
        text = text.replaceAll("/[^\\s]+", "").replaceAll("[A-Za-z0-9_+=:-]{24,}", "");
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private void save() throws Exception {
        store.write(state);
    }
}
