package com.music.bitchord.data.model;

import java.util.List;

/** Pinned BitChord 1.9 Song API used by host and framework fixtures. */
public final class Song {
    public final String videoId;
    public final String title;
    public final String artist;
    public final String thumbnailUrl;
    public final String durationText;
    public final String artistId;
    public final List<?> artists;
    public final String albumId;
    public final String albumName;
    public final boolean isVideo;
    public final boolean isVideoOrigin;
    public final String setVideoId;
    public final QueueTier queueTier;
    public final String queueEntryId;
    public final String radioName;
    public final String localUri;
    public final String downloadFormat;
    public final String localPath;
    public final Long localDateAddedSeconds;
    public final Long localDateModifiedSeconds;
    public final Integer trackNumber;
    public final String sourceQuality;
    public final boolean sourceHiRes;
    public final Boolean isExplicit;
    public final String playbackSource;
    public final PlaybackSourceType playbackSourceType;
    public final String playbackSourceId;
    public final String feedbackToken;

    public Song(
            String videoId,
            String title,
            String artist,
            String thumbnailUrl,
            String durationText,
            String artistId,
            List<?> artists,
            String albumId,
            String albumName,
            boolean isVideo,
            boolean isVideoOrigin,
            String setVideoId,
            QueueTier queueTier,
            String queueEntryId,
            String radioName,
            String localUri,
            String downloadFormat,
            String localPath,
            Long localDateAddedSeconds,
            Long localDateModifiedSeconds,
            Integer trackNumber,
            String sourceQuality,
            boolean sourceHiRes,
            Boolean isExplicit,
            String playbackSource,
            PlaybackSourceType playbackSourceType,
            String playbackSourceId,
            String feedbackToken) {
        this.videoId = videoId;
        this.title = title;
        this.artist = artist;
        this.thumbnailUrl = thumbnailUrl;
        this.durationText = durationText;
        this.artistId = artistId;
        this.artists = artists == null ? java.util.Collections.emptyList() : artists;
        this.albumId = albumId;
        this.albumName = albumName;
        this.isVideo = isVideo;
        this.isVideoOrigin = isVideoOrigin;
        this.setVideoId = setVideoId;
        this.queueTier = queueTier;
        this.queueEntryId = queueEntryId;
        this.radioName = radioName;
        this.localUri = localUri;
        this.downloadFormat = downloadFormat;
        this.localPath = localPath;
        this.localDateAddedSeconds = localDateAddedSeconds;
        this.localDateModifiedSeconds = localDateModifiedSeconds;
        this.trackNumber = trackNumber;
        this.sourceQuality = sourceQuality;
        this.sourceHiRes = sourceHiRes;
        this.isExplicit = isExplicit;
        this.playbackSource = playbackSource;
        this.playbackSourceType = playbackSourceType;
        this.playbackSourceId = playbackSourceId;
        this.feedbackToken = feedbackToken;
    }

    public Song(String videoId, String title, String artist, String durationText) {
        this(
                videoId,
                title,
                artist,
                null,
                durationText,
                null,
                java.util.Collections.emptyList(),
                null,
                null,
                false,
                false,
                null,
                QueueTier.CONTEXT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }
}
