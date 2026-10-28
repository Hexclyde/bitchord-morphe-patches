package app.bitchord.telegram;

import android.content.Context;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;

/** In-memory bridge for UI tests. */
public final class TelegramBridge {
    public static TelegramBridge instance;
    public static String startupError = "";
    public final ExecutorService work = Executors.newSingleThreadExecutor();
    public final Set<Long> selected = new HashSet<>();
    long generation = 1, mediaRevision;
    String sessionTag = "framework-session";
    final ChannelSponsorship sponsorship =
            new ChannelSponsorship(request -> this.backend.call(request));
    final NativeAudio audio = new NativeAudio(this);

    interface NativeBackend {
        TdApi.Object call(TdApi.Function<?> request) throws Exception;
    }

    NativeBackend backend;

    @SuppressWarnings("unchecked")
    <T extends TdApi.Object> T call(TdApi.Function<T> request) throws Exception {
        if (backend == null) throw new java.io.IOException("No native fixture");
        return (T) backend.call(request);
    }

    <T extends TdApi.Object> T call(TdApi.Function<T> request, long timeout) throws Exception {
        return call(request);
    }

    void requireReady() throws java.io.IOException {
        if (!ready()) throw new java.io.IOException("Sign in first");
    }

    void optimize(long bytes) {}

    void cancelDownload(int id) {}

    String url() {
        return InternalAddon.ORIGIN + sessionTag;
    }

    final List<JSONObject> searchResults = new ArrayList<>();

    boolean useTrackNames = true;
    final FormatProbe formats = new FormatProbe(null);

    void probeLater(JSONObject row) {}

    boolean formatLegend = true;

    boolean formatLegend() {
        return formatLegend;
    }

    void setFormatLegend(boolean on) {
        formatLegend = on;
    }

    boolean reportQuality = true, showArtwork = true, namesFromFiles = true;

    boolean reportQuality() {
        return reportQuality;
    }

    void setReportQuality(boolean on) {
        reportQuality = on;
    }

    boolean showArtwork() {
        return showArtwork;
    }

    void setShowArtwork(boolean on) {
        showArtwork = on;
    }

    boolean namesFromFiles() {
        return namesFromFiles;
    }

    void setNamesFromFiles(boolean on) {
        namesFromFiles = on;
    }

    void decorate(List<JSONObject> rows) {}

    boolean surroundAsStereo = true, smoothUpgrades = true;

    boolean smoothUpgrades() {
        return smoothUpgrades;
    }

    void setSmoothUpgrades(boolean on) {
        smoothUpgrades = on;
    }

    boolean surroundAsStereo() {
        return surroundAsStereo;
    }

    void setSurroundAsStereo(boolean on) {
        surroundAsStereo = on;
    }

    boolean useTrackNames() {
        return useTrackNames;
    }

    void setUseTrackNames(boolean on) {
        useTrackNames = on;
    }

    List<JSONObject> searchTracks(String query) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        for (JSONObject row : searchResults)
            if (TrackNames.words(row.optString("title") + " " + row.optString("artist"))
                    .containsAll(TrackNames.words(query))) found.add(row);
        if (!useTrackNames) return found;
        return names.merge(
                found,
                query,
                id -> {
                    for (JSONObject row : searchResults)
                        if (id.equals(row.optString("id"))) return row;
                    return null;
                });
    }

    JSONObject resolveTrack(String id) throws Exception {
        return TrackMetadata.resolve(
                id, selected, (chat, message) -> call(new TdApi.GetMessage(chat, message)));
    }

    int played;
    String playedId;
    final TrackNames names = new TrackNames(null);
    boolean suggestTrackNames = true;
    final List<String> heard = new ArrayList<>();

    boolean suggestTrackNames() {
        return suggestTrackNames;
    }

    void setSuggestTrackNames(boolean on) {
        suggestTrackNames = on;
    }

    final List<String> forgotten = new ArrayList<>();

    void heard(String title, String artist, long durationMs, String mediaId) {
        heard.add(title + "|" + artist + "|" + durationMs + "|" + mediaId);
        try {
            names.played(mediaId, title, artist, (int) (durationMs / 1000));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void rename(JSONObject row, String title, String artist) throws Exception {
        names.correct(row, title, artist);
        if (useTrackNames) forgotten.addAll(names.playedAs(title, artist));
    }

    void acceptSuggestion(String id) throws Exception {
        for (JSONObject suggestion : names.suggestions())
            if (id.equals(suggestion.optString("id"))) {
                names.accept(id);
                if (useTrackNames)
                    forgotten.addAll(
                            names.playedAs(
                                    suggestion.optString("title"), suggestion.optString("artist")));
            }
    }

    void played(String id) {
        played++;
        playedId = id;
    }

    void verifyMedia(long chat, long message, int file) throws Exception {
        if (resolveTrack(chat + "_" + message) == null)
            throw new java.io.IOException("Unavailable");
    }

    public TdApi.Chat channel(long id) throws Exception {
        return call(new TdApi.GetChat(id));
    }

    public boolean hasSponsoredMessages(TdApi.Chat chat) {
        return chat.type instanceof TdApi.ChatTypeSupergroup
                && ((TdApi.ChatTypeSupergroup) chat.type).isChannel;
    }

    public void openChannel(long id) throws Exception {
        call(new TdApi.OpenChat(id));
    }

    public void closeChannel(long id) {
        try {
            call(new TdApi.CloseChat(id));
        } catch (Exception ignored) {
        }
    }

    public List<TdApi.Message> channelHistory(long chat, long before) throws Exception {
        return Arrays.asList(call(new TdApi.GetChatHistory(chat, before, 0, 30, false)).messages);
    }

    public void viewedMessages(long chat, long[] ids, long session) throws Exception {
        if (session != generation) throw new java.io.IOException("Account changed");
        call(new TdApi.ViewMessages(chat, ids, new TdApi.MessageSourceChatHistory(), false));
    }

    public String messageLink(long chat, long message) throws Exception {
        TdApi.GetMessageLink request = new TdApi.GetMessageLink();
        request.chatId = chat;
        request.messageId = message;
        request.pollOptionId = "";
        return call(request).link;
    }

    public String downloadSponsorFile(int file) throws Exception {
        return call(new TdApi.DownloadFile(file, 16, 0, 0, true)).local.path;
    }

    void releaseSponsorFile(int file) {}

    int sponsorColor(int id, boolean dark) {
        return 0xff438d59;
    }

    public TdApi.AuthorizationState auth = new TdApi.AuthorizationStateWaitTdlibParameters();
    public String status = "Add your application credentials to connect.", error = "";
    ApplicationCredentials credentials = ApplicationCredentials.empty();

    public static void init(Context context) {
        if (instance == null) instance = new TelegramBridge();
    }

    public ApplicationCredentials credentials() {
        return credentials;
    }

    public boolean ready() {
        return auth instanceof TdApi.AuthorizationStateReady;
    }

    public void saveCredentials(String id, String hash) {
        credentials = ApplicationCredentials.parse(id, hash);
        auth = new TdApi.AuthorizationStateWaitPhoneNumber();
        status = "Enter your phone number.";
    }

    public void configure() {}

    public void submit(String text) {
        if (auth instanceof TdApi.AuthorizationStateWaitPhoneNumber)
            auth = new TdApi.AuthorizationStateWaitCode();
        else {
            auth = new TdApi.AuthorizationStateReady();
            status = "Telegram connected.";
        }
    }

    public List<TdApi.Chat> chats() {
        return new ArrayList<>();
    }

    public void selectChats(List<TdApi.Chat> chats, boolean[] values) {}

    public void addChat(String text) {}

    public void registerSource() {}

    public long cacheBytes = 256L * 1024 * 1024;

    public long cacheLimitBytes() {
        return cacheBytes;
    }

    public void setCacheLimitBytes(long bytes) {
        CachePolicy.requireValid(bytes);
        cacheBytes = bytes;
    }

    public void clearCache() {}

    public void logout() {
        auth = new TdApi.AuthorizationStateWaitPhoneNumber();
    }

    public void fail(Exception e) {
        error = e.getMessage();
    }
}
