package app.bitchord.soulseek;

final class SoulseekDownloadRoute {
    static boolean ownsTrack(String id) {
        return id != null && id.startsWith("src:soulseek-native::");
    }
}
