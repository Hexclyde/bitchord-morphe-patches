package app.bitchord.telegram;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** The page's owners, its saved rules, and the once-a-day pass those rules ask for. */
public final class StorageSenseAuto {
    private StorageSenseAuto() {}

    static final String PREFS = "bitchord_storage_sense";
    static final long DAY_MS = 86_400_000L, START_DELAY_MS = 120_000L;

    static StorageSense.Rules rules(Context context) {
        SharedPreferences saved = context.getSharedPreferences(PREFS, 0);
        StorageSense.Rules rules = new StorageSense.Rules();
        rules.ageOn = saved.getBoolean("ageOn", false);
        rules.sizeOn = saved.getBoolean("sizeOn", false);
        rules.ageDays = StorageSense.Rules.validDays(saved.getInt("ageDays", rules.ageDays));
        rules.maxBytes = StorageSense.Rules.validBytes(saved.getLong("maxBytes", rules.maxBytes));
        return rules;
    }

    static void save(Context context, StorageSense.Rules rules) {
        context.getSharedPreferences(PREFS, 0)
                .edit()
                .putBoolean("ageOn", rules.ageOn)
                .putBoolean("sizeOn", rules.sizeOn)
                .putInt("ageDays", StorageSense.Rules.validDays(rules.ageDays))
                .putLong("maxBytes", StorageSense.Rules.validBytes(rules.maxBytes))
                .apply();
    }

    static String lastResult(Context context) {
        return context.getSharedPreferences(PREFS, 0).getString("lastResult", "");
    }

    static StorageSenseEngine engine(Context context) {
        Context app = context.getApplicationContext();
        StorageSenseEngine.Clock clock = System::currentTimeMillis;
        List<StorageSense.Owner> owners = new ArrayList<>();
        owners.add(
                new StorageSenseHostCache(
                        StorageSenseHostCache.Reflective.of(
                                "com.music.bitchord.playback.AudioCache"),
                        clock));
        try {
            ClassLoader loader = StorageSenseAuto.class.getClassLoader();
            // The bridge is what proves Telegram was added, wherever this owner's class lands.
            Class.forName("app.bitchord.telegram.TelegramBridge", false, loader);
            owners.add(
                    (StorageSense.Owner)
                            Class.forName("app.bitchord.telegram.StorageSenseTelegram")
                                    .getConstructor()
                                    .newInstance());
        } catch (ReflectiveOperationException | LinkageError notSelected) {
            // Telegram wasn't added to this install, so the page has no such category.
        }
        owners.add(new StorageSensePatchCaches(app));
        owners.addAll(StorageSense.registered());
        owners.add(new StorageSenseSaved(app, new StorageSenseSaved.HostRecords()));
        return new StorageSenseEngine(owners, StorageSenseActive::capture, clock);
    }

    static boolean due(StorageSense.Rules rules, long lastRunMs, long now) {
        return rules.any() && (lastRunMs <= 0 || lastRunMs > now || now - lastRunMs >= DAY_MS);
    }

    /** Applies a plan and reports how it went. Only ever given cache items by the planner. */
    static boolean runRules(StorageSenseEngine engine, StorageSense.Rules rules, Context app) {
        StorageSenseEngine.Snapshot snapshot = engine.load(StorageSense.NEVER);
        List<StorageSense.Item> items = snapshot.items();
        for (StorageSense.Item item : items)
            if (item.block == StorageSense.Block.STATE_UNKNOWN) return false;
        StorageSense.Plan plan = StorageSense.plan(items, rules, engine.clock.now());
        StorageSenseEngine.Outcome outcome = engine.remove(plan.items, StorageSense.NEVER);
        if (app != null)
            app.getSharedPreferences(PREFS, 0)
                    .edit()
                    .putLong("lastRun", engine.clock.now())
                    .putString(
                            "lastResult",
                            Strings.f(
                                    "Last cleanup removed {0} items, {1}",
                                    String.valueOf(outcome.removed),
                                    StorageSense.size(outcome.bytes)))
                    .apply();
        return true;
    }

    /**
     * Called once as the app starts. Does nothing at all unless a rule was turned on, and waits so
     * it never competes with the first song. Opening the Storage Sense page never gets here.
     */
    public static void start(Context context) {
        Context app = context.getApplicationContext();
        StorageSense.Rules rules = rules(app);
        long last = app.getSharedPreferences(PREFS, 0).getLong("lastRun", 0);
        if (!due(rules, last, System.currentTimeMillis())) return;
        Thread pass =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(START_DELAY_MS);
                                StorageSense.Rules now = rules(app);
                                if (now.any()) runRules(engine(app), now, app);
                            } catch (InterruptedException stopped) {
                                Thread.currentThread().interrupt();
                            } catch (Exception | LinkageError unavailable) {
                                HostLog.d("Storage cleanup was skipped");
                            }
                        },
                        "storage-sense-rules");
        pass.setDaemon(true);
        pass.setPriority(Thread.MIN_PRIORITY);
        pass.start();
    }
}
