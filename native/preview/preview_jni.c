#include <errno.h>
#include <jni.h>
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
#include <libswresample/swresample.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

/* Each extractor owns one context, used and released on its Media3 loading
 * thread. */
typedef struct {
  JNIEnv *env;
  jobject input;
  jbyteArray buffer;
  jmethodID read, seek;
  AVFormatContext *format;
  AVIOContext *io;
  AVCodecContext *codec;
  SwrContext *swr;
  AVFrame *frame;
  AVPacket *packet;
  int stream, draining, rate, channels;
  enum AVSampleFormat sample_format;
  int64_t time_us, samples;
} Preview;

static int read_input(void *opaque, uint8_t *buf, int size) {
  Preview *p = opaque;
  JNIEnv *e = p->env;
  if ((*e)->ExceptionCheck(e))
    return AVERROR_EXIT;
  if (size > 32768)
    size = 32768;
  jint n = (*e)->CallIntMethod(e, p->input, p->read, p->buffer, 0, size);
  if ((*e)->ExceptionCheck(e))
    return AVERROR_EXIT;
  if (n < 0)
    return AVERROR_EOF;
  if (n == 0)
    return AVERROR(EIO);
  (*e)->GetByteArrayRegion(e, p->buffer, 0, n, (jbyte *)buf);
  return n;
}
static int64_t seek_input(void *opaque, int64_t offset, int whence) {
  Preview *p = opaque;
  if ((*p->env)->ExceptionCheck(p->env))
    return AVERROR_EXIT;
  int64_t at = (*p->env)->CallLongMethod(p->env, p->input, p->seek,
                                         (jlong)offset, (jint)whence);
  return (*p->env)->ExceptionCheck(p->env) ? AVERROR_EXIT : at;
}
static void failure(JNIEnv *e, const char *operation, int error) {
  if ((*e)->ExceptionCheck(e))
    return;
  char detail[AV_ERROR_MAX_STRING_SIZE], message[256];
  av_strerror(error, detail, sizeof(detail));
  snprintf(message, sizeof(message), "%s: %s", operation, detail);
  (*e)->ThrowNew(e, (*e)->FindClass(e, "java/io/IOException"), message);
}
static void destroy(JNIEnv *e, Preview *p) {
  if (!p)
    return;
  av_packet_free(&p->packet);
  av_frame_free(&p->frame);
  swr_free(&p->swr);
  avcodec_free_context(&p->codec);
  avformat_close_input(&p->format);
  if (p->io)
    av_freep(&p->io->buffer);
  avio_context_free(&p->io);
  if (p->input)
    (*e)->DeleteGlobalRef(e, p->input);
  if (p->buffer)
    (*e)->DeleteGlobalRef(e, p->buffer);
  av_free(p);
}
JNIEXPORT jlong JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_open(
    JNIEnv *e, jclass cls, jobject input) {
  (void)cls;
  Preview *p = av_mallocz(sizeof(*p));
  if (!p) {
    failure(e, "Preview allocation", AVERROR(ENOMEM));
    return 0;
  }
  av_max_alloc(64 * 1024 * 1024);
  p->env = e;
  p->input = (*e)->NewGlobalRef(e, input);
  jbyteArray bytes = (*e)->NewByteArray(e, 32768);
  p->buffer = bytes ? (*e)->NewGlobalRef(e, bytes) : NULL;
  if (bytes)
    (*e)->DeleteLocalRef(e, bytes);
  jclass type = (*e)->GetObjectClass(e, input);
  p->read = (*e)->GetMethodID(e, type, "read", "([BII)I");
  p->seek = (*e)->GetMethodID(e, type, "seek", "(JI)J");
  (*e)->DeleteLocalRef(e, type);
  if ((*e)->ExceptionCheck(e) || !p->input || !p->buffer) {
    destroy(e, p);
    return 0;
  }
  uint8_t *buffer = av_malloc(32768);
  p->io = buffer ? avio_alloc_context(buffer, 32768, 0, p, read_input, NULL,
                                      seek_input)
                 : NULL;
  p->format = avformat_alloc_context();
  int result = AVERROR(ENOMEM);
  if (!p->io || !p->format) {
    if (!p->io)
      av_free(buffer);
    goto failed;
  }
  p->format->pb = p->io;
  p->format->flags |= AVFMT_FLAG_CUSTOM_IO;
  p->format->max_streams = 8;
  p->format->max_index_size = 1024 * 1024;
  p->format->probesize = 1024 * 1024;
  p->format->max_analyze_duration = 3000000;
  result = avformat_open_input(&p->format, NULL, NULL, NULL);
  if (result < 0 || (*e)->ExceptionCheck(e))
    goto failed;
  result = avformat_find_stream_info(p->format, NULL);
  if (result < 0 || (*e)->ExceptionCheck(e))
    goto failed;
  result = av_find_best_stream(p->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
  if (result < 0)
    goto failed;
  p->stream = result;
  AVCodecParameters *params = p->format->streams[p->stream]->codecpar;
  const AVCodec *decoder = avcodec_find_decoder(params->codec_id);
  if (!decoder) {
    result = AVERROR_DECODER_NOT_FOUND;
    goto failed;
  }
  p->codec = avcodec_alloc_context3(decoder);
  if (!p->codec) {
    result = AVERROR(ENOMEM);
    goto failed;
  }
  result = avcodec_parameters_to_context(p->codec, params);
  if (result < 0)
    goto failed;
  p->codec->thread_count = 1;
  result = avcodec_open2(p->codec, decoder, NULL);
  if (result < 0)
    goto failed;
  if (p->codec->sample_rate < 1 || p->codec->sample_rate > 1536000 ||
      p->codec->ch_layout.nb_channels < 1 ||
      p->codec->ch_layout.nb_channels > 8) {
    result = AVERROR_INVALIDDATA;
    goto failed;
  }
  p->rate = p->codec->sample_rate;
  p->channels = p->codec->ch_layout.nb_channels;
  p->sample_format = p->codec->sample_fmt;
  result =
      swr_alloc_set_opts2(&p->swr, &p->codec->ch_layout, AV_SAMPLE_FMT_S32,
                          p->codec->sample_rate, &p->codec->ch_layout,
                          p->codec->sample_fmt, p->codec->sample_rate, 0, NULL);
  if (result < 0 || (result = swr_init(p->swr)) < 0)
    goto failed;
  p->frame = av_frame_alloc();
  p->packet = av_packet_alloc();
  if (!p->frame || !p->packet) {
    result = AVERROR(ENOMEM);
    goto failed;
  }
  return (jlong)(intptr_t)p;
failed:
  failure(e, "Cannot decode this preview", result);
  destroy(e, p);
  return 0;
}
JNIEXPORT jlongArray JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_info(
    JNIEnv *e, jclass cls, jlong handle) {
  (void)cls;
  Preview *p = (Preview *)(intptr_t)handle;
  jlong values[4] = {p->codec->sample_rate, p->codec->ch_layout.nb_channels,
                     p->codec->bits_per_raw_sample
                         ? p->codec->bits_per_raw_sample
                         : av_get_bits_per_sample(p->codec->codec_id),
                     p->format->duration};
  jlongArray array = (*e)->NewLongArray(e, 4);
  if (array)
    (*e)->SetLongArrayRegion(e, array, 0, 4, values);
  return array;
}
JNIEXPORT jstring JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_codec(
    JNIEnv *e, jclass cls, jlong handle) {
  (void)cls;
  Preview *p = (Preview *)(intptr_t)handle;
  return (*e)->NewStringUTF(e, avcodec_get_name(p->codec->codec_id));
}
JNIEXPORT jbyteArray JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_next(
    JNIEnv *e, jclass cls, jlong handle) {
  (void)cls;
  Preview *p = (Preview *)(intptr_t)handle;
  p->env = e;
  int result;
  for (;;) {
    result = avcodec_receive_frame(p->codec, p->frame);
    if (result == 0)
      break;
    if (result == AVERROR_EOF)
      return NULL;
    if (result != AVERROR(EAGAIN)) {
      failure(e, "Audio decode", result);
      return NULL;
    }
    if (p->draining)
      return NULL;
    do {
      result = av_read_frame(p->format, p->packet);
      if ((*e)->ExceptionCheck(e))
        return NULL;
      if (result < 0) {
        if (result != AVERROR_EOF) {
          failure(e, "Audio read", result);
          return NULL;
        }
        p->draining = 1;
        result = avcodec_send_packet(p->codec, NULL);
        break;
      }
      if (p->packet->stream_index == p->stream) {
        result = avcodec_send_packet(p->codec, p->packet);
        av_packet_unref(p->packet);
        break;
      }
      av_packet_unref(p->packet);
    } while (1);
    if (result < 0) {
      failure(e, "Audio packet", result);
      return NULL;
    }
  }
  if (p->frame->format != p->sample_format ||
      p->frame->ch_layout.nb_channels != p->channels ||
      (p->frame->sample_rate != 0 && p->frame->sample_rate != p->rate)) {
    failure(e, "Audio format changed during preview", AVERROR_INVALIDDATA);
    return NULL;
  }
  int frames = p->frame->nb_samples, channels = p->channels;
  if (frames < 0 || frames > 1048576) {
    failure(e, "Audio frame", AVERROR_INVALIDDATA);
    return NULL;
  }
  int size =
      av_samples_get_buffer_size(NULL, channels, frames, AV_SAMPLE_FMT_S32, 1);
  if (size < 0) {
    failure(e, "Audio size", size);
    return NULL;
  }
  uint8_t *pcm = av_malloc(size);
  if (!pcm) {
    failure(e, "Audio allocation", AVERROR(ENOMEM));
    return NULL;
  }
  int count = swr_convert(p->swr, &pcm, frames,
                          (const uint8_t **)p->frame->extended_data, frames);
  jbyteArray output = NULL;
  if (count < 0)
    failure(e, "Audio conversion", count);
  else {
    /* A sample clock avoids coarse or absent container timestamps drifting
     * backward. */
    p->time_us =
        av_rescale_q(p->samples, (AVRational){1, p->rate}, AV_TIME_BASE_Q);
    p->samples += count;
    output = (*e)->NewByteArray(e, count * channels * 4);
    if (output)
      (*e)->SetByteArrayRegion(e, output, 0, count * channels * 4,
                               (jbyte *)pcm);
  }
  av_free(pcm);
  av_frame_unref(p->frame);
  return output;
}
JNIEXPORT jlong JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_time(
    JNIEnv *e, jclass cls, jlong handle) {
  (void)e;
  (void)cls;
  return ((Preview *)(intptr_t)handle)->time_us;
}
JNIEXPORT void JNICALL Java_app_bitchord_soulseek_NativePreviewAudio_close(
    JNIEnv *e, jclass cls, jlong handle) {
  (void)cls;
  destroy(e, (Preview *)(intptr_t)handle);
}
