package com.music.bitchord.download;

/** Container and probe-file ABI inspected in the released BitChord1.9 APK. */
public final class Downloader {
    public enum Container {
        FLAC,
        WAV,
        MP3,
        MP4,
        ADTS,
        OGG,
        WEBM
    }

    public abstract static class Probe {
        public static final class File extends Probe {
            public final Container container;
            public final Long totalBytes;

            public File(Container container, Long totalBytes) {
                this.container = container;
                this.totalBytes = totalBytes;
            }
        }
    }
}
