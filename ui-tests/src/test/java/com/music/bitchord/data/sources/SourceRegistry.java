package com.music.bitchord.data.sources;

import kotlinx.coroutines.flow.*;

import java.util.*;

public final class SourceRegistry {
    public static final MutableStateFlow<List<SourceConfig>> configs =
            StateFlowKt.MutableStateFlow(new ArrayList<>());

    public static final List<MusicSource> downloadSources = new ArrayList<>();
    public static List<SourceConfig> persistedConfigs = new ArrayList<>();

    public static List<MusicSource> active() {
        List<MusicSource> active = new ArrayList<>();
        for (MusicSource source : downloadSources) {
            SourceConfig config = config(source.getConfigId());
            if (config != null && config.enabled) active.add(source);
        }
        return active;
    }

    public static List<MusicSource> activeForDownload() {
        List<MusicSource> active = new ArrayList<>();
        for (MusicSource source : downloadSources) {
            SourceConfig config = config(source.getConfigId());
            if (config != null && config.enabled && config.allowDownloads) active.add(source);
        }
        return active;
    }

    public static void publish(ArrayList<SourceConfig> next, boolean persist) {
        configs.setValue(next);
        if (persist) persistedConfigs = new ArrayList<>(next);
    }

    public static SourceConfig config(String id) {
        for (SourceConfig row : configs.getValue()) if (id.equals(row.id)) return row;
        return null;
    }

    public static void setEnabled(String id, boolean enabled) {
        ArrayList<SourceConfig> next = new ArrayList<>();
        for (SourceConfig row : configs.getValue())
            next.add(
                    id.equals(row.id)
                            ? new SourceConfig(
                                    row.id,
                                    row.kind,
                                    row.label,
                                    row.baseUrl,
                                    enabled,
                                    row.allowDownloads,
                                    row.checkValidLossless)
                            : row);
        publish(next, true);
    }
}
