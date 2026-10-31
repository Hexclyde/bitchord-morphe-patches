package app.bitchord.telegram;
import org.json.*;
public final class MixedSongCodecTest {
    static int checks;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        JSONObject data=new JSONObject().put("videoId","src:telegram-native::channel_1").put("title","Original").put("artist","Artist").put("sourceQuality","LOSSLESS").put("sourceHiRes",true).put("isVideo",true).put("playbackSourceType","SEARCH").put("localPath","/storage/original.flac").put("localUri","content://music/42").put("downloadFormat","FLAC").put("localDateAddedSeconds",123456789L).put("trackNumber",8).put("queueEntryId","stale-queue").put("feedbackToken","do-not-save");
        Object file=MixedSongCodec.decode(data);JSONObject saved=MixedSongCodec.encode(file);Object restored=MixedSongCodec.decode(new JSONObject(saved.toString()));
        for(String key:new String[]{"videoId","title","artist","sourceQuality","sourceHiRes","isVideo","playbackSourceType","localPath","localUri","downloadFormat","localDateAddedSeconds","trackNumber"}) check(saved.get(key).equals(MixedSongCodec.encode(restored).get(key)),"round trip "+key);
        check(!saved.has("queueEntryId")&&!saved.has("feedbackToken"),"ephemeral queue/account tokens excluded");
        check(MixedSongCodec.key(restored).equals("file:content://music/42"),"device identity uses real content URI");
        Object stream=MixedSongCodec.decode(new JSONObject().put("videoId","src:custom::audio-1").put("title","Stream").put("artist","Artist"));
        JSONObject streamed=MixedSongCodec.encode(stream);check(!streamed.has("localUri"),"stream has no invented file URI");check(streamed.getString("videoId").equals("src:custom::audio-1"),"addon source identity retained");
        check(MixedSongCodec.key(stream).equals("stream:src:custom::audio-1"),"provider-bound stream key");
        Object yt=MixedSongCodec.decode(new JSONObject().put("videoId","dQw4w9WgXcQ").put("title","YT").put("artist","Artist"));check(MixedSongCodec.key(yt).equals("stream:dQw4w9WgXcQ"),"YouTube identity retained");
        Object selected=MixedSongCodec.file("content://downloads/77","Downloaded file");check(MixedSongCodec.key(selected).equals("file:content://downloads/77"),"download URI retained");
        try{MixedSongCodec.decode(new JSONObject().put("title","Invalid"));throw new AssertionError("missing identity");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" mixed-source Song persistence checks.");
    }
}
