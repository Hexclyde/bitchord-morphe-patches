package app.bitchord.telegram;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic backups: when they run, that a send is only done once Telegram holds the file, that an
 * uncertain send is looked for and never repeated blindly, and that only this app's backups are
 * ever removed.
 */
public final class AutoBackupTest {
    static int passed;

    static void check(boolean okay, String message) {
        passed++;
        if (!okay) throw new AssertionError(message);
    }

    static final long HOUR = 3600_000L;
    static final long CHANNEL = -1001L;

    static final class MemoryStore implements AutoBackup.Store {
        String saved;

        public JSONObject read() throws Exception {
            return saved == null ? null : new JSONObject(saved);
        }

        public void write(JSONObject state) {
            saved = state.toString();
        }
    }

    static final class FakeHost implements AutoBackup.Host {
        final AtomicLong clock;
        final File folder;
        boolean ready = true, allowed = true, owned = true, completes = true;
        String account = "acc1";
        int stages, sends, awaits;
        AutoBackup.FloodWait flood;
        boolean dropsAfterQueue;
        long nextId = 100;
        final Map<Long, AutoBackup.Sent> chat = new TreeMap<>();
        final List<Long> deleted = new ArrayList<>();
        AutoBackup.Contents lastContents;
        long fakeSize = -1;

        FakeHost(AtomicLong clock, File folder) {
            this.clock = clock;
            this.folder = folder;
        }

        public boolean ready() {
            return ready;
        }

        final List<Long> discarded = new ArrayList<>();
        volatile java.util.concurrent.CountDownLatch inAwait, releaseAwait;

        public boolean allowed(boolean manual) {
            return allowed;
        }

        public void discard(long target, long id) {
            discarded.add(id);
            chat.remove(id);
        }

        public String account() {
            return account;
        }

        public boolean ownedPrivateChannel(long id) {
            return owned;
        }

        public File stage(AutoBackup.Contents contents) throws Exception {
            stages++;
            lastContents = contents;
            File file = new File(folder, "bitchord-telegram-backup-20261110-" + stages + ".gz");
            Files.write(file.toPath(), new byte[1000 + stages]);
            return file;
        }

        public long send(long target, File file, String caption) throws Exception {
            if (flood != null) throw flood;
            sends++;
            AutoBackup.Sent s = new AutoBackup.Sent();
            s.chat = target;
            s.id = nextId++;
            s.outgoing = true;
            s.name = file.getName();
            s.size = fakeSize >= 0 ? fakeSize : file.length();
            s.caption = caption;
            s.date = (int) (clock.get() / 1000);
            s.pending = !completes;
            chat.put(s.id, s);
            if (dropsAfterQueue) throw new AutoBackup.Uncertain("connection lost after queueing");
            return s.id;
        }

        public AutoBackup.Sent await(long target, long temporary, long millis) {
            awaits++;
            if (inAwait != null) {
                inAwait.countDown();
                try {
                    releaseAwait.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            AutoBackup.Sent s = chat.get(temporary);
            return s != null && (!s.pending || s.failed) ? s : null;
        }

        public AutoBackup.Sent find(long target, String token) {
            for (AutoBackup.Sent s : chat.values())
                if (s.chat == target && s.caption.endsWith(token)) return s;
            return null;
        }

        public List<AutoBackup.Sent> documents(long target) {
            List<AutoBackup.Sent> all = new ArrayList<>(chat.values());
            Collections.reverse(all);
            return all;
        }

        public void delete(long target, long[] ids) {
            for (long id : ids) {
                deleted.add(id);
                chat.remove(id);
            }
        }

        void complete() {
            for (AutoBackup.Sent s : chat.values()) s.pending = false;
        }

        String token() {
            return chat.values()
                    .iterator()
                    .next()
                    .caption
                    .substring(AutoBackup.MARKER.length() + 1);
        }
    }

    static AutoBackup.Sent foreign(
            FakeHost host, long id, String name, String caption, boolean outgoing, int date) {
        AutoBackup.Sent s = new AutoBackup.Sent();
        s.chat = CHANNEL;
        s.id = id;
        s.name = name;
        s.caption = caption;
        s.outgoing = outgoing;
        s.date = date;
        host.chat.put(id, s);
        return s;
    }

    static AutoBackup open(FakeHost host, MemoryStore store, AtomicLong clock) throws Exception {
        return new AutoBackup(host, store, clock::get);
    }

    public static void main(String[] args) throws Exception {
        File folder = Files.createTempDirectory("auto-backup-test").toFile();
        AtomicLong clock = new AtomicLong(1_800_000_000_000L);
        AtomicBoolean stop = new AtomicBoolean();

        // Interval entry: presets and custom hours, refusing anything that isn't a positive number.
        check(AutoBackup.parseHours(" 6 ") == 6, "custom hours");
        for (String bad :
                new String[] {"0", "-3", "abc", "", "1.5", "99999999999999999999", "8761"}) {
            boolean refused = false;
            try {
                AutoBackup.parseHours(bad);
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            check(refused, "refuses " + bad);
        }
        check(AutoBackup.parseHours("8760") == 8760, "a year is allowed");

        // A first backup: staged, sent, confirmed by the posted file, staging removed, next due
        // set.
        FakeHost host = new FakeHost(clock, folder);
        MemoryStore store = new MemoryStore();
        AutoBackup backup = open(host, store, clock);
        check(backup.delayMillis() == -1, "nothing planned while off");
        backup.setTarget(CHANNEL, "Backups");
        backup.setContents(AutoBackup.Contents.BOTH);
        backup.setIntervalHours(6);
        backup.setKeep(2);
        backup.setEnabled(true);
        check(backup.delayMillis() == 0, "due at once when first turned on");
        // Older backups: two of this app's, one of someone else's with the same file name, one with
        // a caption of our kind posted by another account.
        foreign(
                host,
                1,
                "bitchord-telegram-backup-20261001-a1.gz",
                AutoBackup.MARKER + " aaaaaaaaaaa1",
                true,
                10);
        foreign(
                host,
                2,
                "bitchord-telegram-backup-20261002-b2.gz",
                AutoBackup.MARKER + " aaaaaaaaaaa2",
                true,
                20);
        foreign(host, 3, "bitchord-telegram-backup-20261003-c.gz", "my own note", true, 5);
        foreign(
                host,
                4,
                "bitchord-telegram-backup-20261004-d.gz",
                AutoBackup.MARKER + " bbbbbbbbbbbb",
                false,
                6);
        foreign(host, 5, "holiday.gz", AutoBackup.MARKER + " cccccccccccc", true, 7);
        // Lookalikes: a manual backup, a caption with extra text, a name that isn't ours.
        foreign(host, 6, "bitchord-telegram-backup-20260901-f.gz", "BitChord backup", true, 1);
        foreign(
                host,
                7,
                "bitchord-telegram-backup-20260902-g.gz",
                AutoBackup.MARKER + " eeeeeeeeeeee and more",
                true,
                2);
        foreign(
                host,
                8,
                "bitchord-telegram-backup-notes.gz",
                AutoBackup.MARKER + " ffffffffffff",
                true,
                3);
        AutoBackup.Outcome done = backup.run(stop);
        check(host.sends == 1 && host.stages == 1, "one backup staged and sent");
        check(backup.lastSuccess() == clock.get() && backup.error().isEmpty(), "success recorded");
        check(!backup.pending(), "transaction closed");
        check(
                new File(folder, "bitchord-telegram-backup-20261110-1.gz").exists() == false,
                "staging removed once confirmed");
        check(
                done.delayMillis == 6 * HOUR && backup.nextAt() == clock.get() + 6 * HOUR,
                "next due is an interval away");
        check(
                host.deleted.equals(Arrays.asList(1L)),
                "only the oldest of this app's own backups went: " + host.deleted);
        check(
                host.chat.containsKey(3L)
                        && host.chat.containsKey(4L)
                        && host.chat.containsKey(5L)
                        && host.chat.containsKey(6L)
                        && host.chat.containsKey(7L)
                        && host.chat.containsKey(8L),
                "other posts and lookalikes untouched");

        // Not due yet: nothing is staged, and a restart does not make it due.
        clock.addAndGet(HOUR);
        int stages = host.stages;
        check(
                backup.run(stop).delayMillis == 5 * HOUR && host.stages == stages,
                "waits for the interval");
        AutoBackup reopened = open(host, store, clock);
        check(
                reopened.run(stop).delayMillis == 5 * HOUR && host.stages == stages,
                "a restart keeps the due time");

        // Missed intervals (phone off for days) make one backup, not one for each.
        clock.addAndGet(100 * HOUR);
        reopened = open(host, store, clock);
        reopened.run(stop);
        check(
                host.stages == stages + 1 && host.sends == 2,
                "one backup for all the missed intervals");
        check(reopened.nextAt() == clock.get() + 6 * HOUR, "and the next is an interval from now");

        // Playback, preview or a search wants the network: nothing is staged, nothing is sent.
        clock.addAndGet(7 * HOUR);
        host.allowed = false;
        stages = host.stages;
        check(
                reopened.run(stop).delayMillis == AutoBackup.BUSY_MILLIS && host.stages == stages,
                "yields to playback");
        host.allowed = true;
        stop.set(true);
        check(
                reopened.run(stop).delayMillis == AutoBackup.BUSY_MILLIS && host.stages == stages,
                "stops when asked");
        stop.set(false);

        // A backup the user asks for runs now, whatever the interval.
        int sends = host.sends;
        reopened.runNow(stop);
        check(host.sends == sends + 1, "back up now sends");
        reopened.runNow(stop);
        check(host.sends == sends + 2, "and can be asked again");

        // Telegram asks for a pause: the staged file waits, nothing is retried tightly, then it
        // goes.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.flood = new AutoBackup.FloodWait(120);
        AutoBackup.Outcome waited = backup.run(stop);
        check(waited.delayMillis == 121_000 && host.sends == 0, "flood wait honoured");
        check(
                backup.pending() && backup.error().contains("wait"),
                "staged file kept and the reason shown");
        stages = host.stages;
        clock.addAndGet(60_000);
        check(
                backup.run(stop).delayMillis == 61_000 && host.stages == stages,
                "not retried before the wait ends");
        host.flood = null;
        clock.addAndGet(61_000);
        backup.run(stop);
        check(
                host.sends == 1 && host.stages == stages && backup.lastSuccess() > 0,
                "sent after the wait without staging again");

        // The send is accepted but never finishes: nothing is counted as done, and it isn't sent
        // twice.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.completes = false;
        AutoBackup.Outcome stuck = backup.run(stop);
        check(
                host.sends == 1 && backup.lastSuccess() == 0 && backup.pending(),
                "accepted is not done");
        check(stuck.delayMillis == AutoBackup.PENDING_MILLIS, "looked at again soon");
        clock.addAndGet(AutoBackup.PENDING_MILLIS);
        backup.run(stop);
        check(host.sends == 1, "still one copy while Telegram holds it pending");
        // The process dies here. A new one finds it by its token once Telegram posts it.
        host.complete();
        AutoBackup revived = open(host, store, clock);
        revived.run(stop);
        check(
                host.sends == 1 && revived.lastSuccess() > 0 && !revived.pending(),
                "recovered by token after process death");

        // An uncertain failure (connection lost after queueing): however long it takes, a message
        // Telegram has no trace of is not sent again by the clock alone.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.dropsAfterQueue = true;
        host.completes = false;
        backup.run(stop);
        check(
                host.sends == 1 && backup.unresolved() && !backup.error().isEmpty(),
                "failure kept as unresolved: " + backup.error());
        host.dropsAfterQueue = false;
        host.chat.clear(); // no trace of it in the chat
        for (int i = 0; i < 6; i++) {
            clock.addAndGet(48 * HOUR);
            backup.run(stop);
        }
        check(host.sends == 1 && host.stages == 1, "never resent because time passed");
        check(
                backup.unresolved() && backup.pending(),
                "still waiting for Telegram, nothing hidden");
        check(
                new File(folder, "bitchord-telegram-backup-20261110-1.gz").exists(),
                "staging kept until it is settled");
        // Telegram finally posts it: that settles it, from the same staged file, without a resend.
        host.completes = true;
        AutoBackup.Sent late = new AutoBackup.Sent();
        late.chat = CHANNEL;
        late.id = 900;
        late.outgoing = true;
        late.name = "bitchord-telegram-backup-20261110-1.gz";
        late.size = new File(folder, "bitchord-telegram-backup-20261110-1.gz").length();
        late.caption =
                AutoBackup.MARKER
                        + " "
                        + store.saved.replaceAll(".*\"token\":\"([0-9a-f]{12})\".*", "$1");
        host.chat.put(900L, late);
        backup.run(stop);
        check(
                host.sends == 1 && backup.lastSuccess() > 0 && !backup.pending(),
                "settled when Telegram posts it, never resent");
        check(
                !new File(folder, "bitchord-telegram-backup-20261110-1.gz").exists(),
                "and only then the staging goes");

        // Telegram reports the send failed: that is authoritative, so a new message is sent.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.completes = false;
        backup.run(stop);
        String firstToken = host.token();
        for (AutoBackup.Sent s : host.chat.values()) {
            s.failed = true;
            s.reason = "Telegram couldn't post the file.";
        }
        clock.addAndGet(AutoBackup.PENDING_MILLIS * 100);
        backup.run(stop);
        check(
                host.discarded.size() == 1 && backup.error().contains("couldn't post"),
                "failed post reported and removed");
        host.completes = true;
        clock.addAndGet(AutoBackup.MAX_RETRY_MILLIS);
        backup.run(stop);
        check(
                host.sends == 2 && host.stages == 1 && backup.lastSuccess() > 0,
                "resent after the failure was reported");
        check(!host.token().equals(firstToken), "with a token of its own");

        // Callback before receipt: Telegram's answer is already there when the id comes back.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        backup.run(stop);
        check(
                backup.lastSuccess() > 0 && host.sends == 1,
                "an answer that came first still counts");

        // What Telegram holds must be the file that was staged.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.fakeSize = 5;
        backup.run(stop);
        check(
                backup.lastSuccess() == 0 && backup.pending() && !backup.error().isEmpty(),
                "a different file is not a success, and its staging is kept");
        check(host.deleted.isEmpty(), "and nothing is removed");

        // Retention never runs unless the new backup is confirmed in the channel, and 0 keeps all.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setKeep(0);
        backup.setEnabled(true);
        for (int i = 0; i < 4; i++)
            foreign(
                    host,
                    10 + i,
                    "bitchord-telegram-backup-2026100" + i + "-" + i + ".gz",
                    AutoBackup.MARKER + " ddddddddddd" + i,
                    true,
                    i);
        backup.run(stop);
        check(host.deleted.isEmpty(), "unlimited keeps everything");
        host.completes = false;
        backup.setKeep(1);
        clock.addAndGet(100 * HOUR);
        backup.run(stop);
        check(
                host.deleted.isEmpty() && backup.lastSuccess() < clock.get(),
                "nothing removed for an unconfirmed backup");

        // A channel that isn't private and owned, or another account: nothing is sent.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.owned = false;
        backup.run(stop);
        check(
                host.sends == 0 && backup.error().contains("private channel"),
                "refuses a channel that isn't owned");
        host.owned = true;
        host.account = "acc2";
        clock.addAndGet(AutoBackup.MAX_RETRY_MILLIS);
        backup.run(stop);
        check(
                host.sends == 0 && backup.error().contains("different Telegram account"),
                "refuses another account's saved channel");

        // Changing the channel drops a staged file; one already handed to Telegram is settled in
        // the channel it went to, with its file kept.
        host = new FakeHost(clock, folder);
        store = new MemoryStore();
        backup = open(host, store, clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.flood = new AutoBackup.FloodWait(30);
        backup.run(stop);
        File staged = new File(folder, "bitchord-telegram-backup-20261110-" + host.stages + ".gz");
        check(staged.exists(), "staged for the old channel");
        backup.setTarget(-1002L, "Other");
        check(!staged.exists() && !backup.pending(), "dropped with the old channel");
        host.flood = null;
        host.completes = false;
        clock.addAndGet(AutoBackup.MAX_RETRY_MILLIS);
        backup.run(stop);
        File uploading =
                new File(folder, "bitchord-telegram-backup-20261110-" + host.stages + ".gz");
        backup.setTarget(CHANNEL, "Backups");
        check(
                uploading.exists() && backup.unresolved(),
                "an upload in flight keeps its file and its place");
        sends = host.sends;
        host.complete();
        clock.addAndGet(AutoBackup.MAX_RETRY_MILLIS);
        backup.run(stop);
        check(
                host.sends == sends && !backup.unresolved() && !uploading.exists(),
                "the first is settled in its own channel, once");
        clock.addAndGet(48 * HOUR);
        backup.run(stop);
        check(host.sends == sends + 1, "then the new channel gets its own backup");

        // Priority: the manual backup yields to audible playback, and checks before staging and
        // before sending.
        host = new FakeHost(clock, folder);
        backup = open(host, new MemoryStore(), clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.allowed = false;
        stages = host.stages;
        backup.runNow(stop);
        check(host.stages == stages && host.sends == 0, "back up now does not outrank playback");
        host.allowed = true;
        backup.runNow(stop);
        check(host.sends == 1, "and goes once things are quiet");

        // The state is never held while Telegram is waited on: settings answer at once, and a
        // second run is refused instead of queueing behind the first.
        host = new FakeHost(clock, folder);
        backup = open(host, new MemoryStore(), clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setEnabled(true);
        host.completes = false;
        host.inAwait = new java.util.concurrent.CountDownLatch(1);
        host.releaseAwait = new java.util.concurrent.CountDownLatch(1);
        final AutoBackup busy = backup;
        Thread runner =
                new Thread(
                        () -> {
                            try {
                                busy.run(stop);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        runner.start();
        check(
                host.inAwait.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the run reached the wait");
        final AutoBackup.Outcome[] second = new AutoBackup.Outcome[1];
        Thread settings =
                new Thread(
                        () -> {
                            try {
                                busy.setKeep(7);
                                busy.setIntervalHours(3);
                                busy.enabled();
                                busy.lastSuccess();
                                busy.error();
                                busy.nextAt();
                                busy.delayMillis();
                                second[0] = busy.run(stop);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
        settings.start();
        settings.join(5000);
        check(!settings.isAlive(), "settings and getters answer while a send is awaited");
        check(
                second[0] != null
                        && second[0].delayMillis == AutoBackup.BUSY_MILLIS
                        && host.sends == 1,
                "a second run is refused, not queued");
        check(busy.keep() == 7 && busy.intervalHours() == 3, "and the changes were kept");
        host.releaseAwait.countDown();
        runner.join(10000);
        check(!runner.isAlive(), "the first run ends");
        host.inAwait = null;

        // Contents choice reaches the staging step.
        host = new FakeHost(clock, folder);
        backup = open(host, new MemoryStore(), clock);
        backup.setTarget(CHANNEL, "Backups");
        backup.setContents(AutoBackup.Contents.SETTINGS);
        backup.setEnabled(true);
        backup.run(stop);
        check(host.lastContents == AutoBackup.Contents.SETTINGS, "settings only");

        // Errors shown never carry paths or long tokens.
        String shown =
                AutoBackup.safe(
                        "failed at /data/user/0/app/files/x.gz with"
                                + " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        check(
                !shown.contains("/data") && !shown.contains("ABCDEFGHIJKLMNOP"),
                "safe error: " + shown);

        System.out.println(
                "PASS: "
                        + passed
                        + " automatic backup schedule, transaction and retention checks.");
    }
}
