package com.music.bitchord.data.sources;
import java.util.*;import kotlinx.coroutines.flow.*;
public final class SourceRegistry {
 public static final MutableStateFlow<List<SourceConfig>> configs=StateFlowKt.MutableStateFlow(new ArrayList<>());
 public static void publish(ArrayList<SourceConfig> next,boolean persist){if(!persist)throw new AssertionError();configs.setValue(next);}
 public static SourceConfig config(String id){for(SourceConfig row:configs.getValue())if(id.equals(row.id))return row;return null;}
 public static void setEnabled(String id,boolean enabled){ArrayList<SourceConfig> next=new ArrayList<>();for(SourceConfig row:configs.getValue())next.add(id.equals(row.id)?new SourceConfig(row.id,row.kind,row.label,row.baseUrl,enabled,row.allowDownloads,row.checkValidLossless):row);publish(next,true);}
}
