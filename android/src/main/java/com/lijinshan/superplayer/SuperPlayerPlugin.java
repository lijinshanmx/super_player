package com.lijinshan.superplayer;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.view.Surface;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterNativeView;
import io.flutter.view.TextureRegistry;
import tv.danmaku.ijk.media.exo.IjkExoMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;


/**
 * @author lijinshan
 */
public class SuperPlayerPlugin implements MethodCallHandler {

    private static class VideoPlayer {

        private IjkExoMediaPlayer mediaPlayer;

        private Surface surface;

        private final TextureRegistry.SurfaceTextureEntry textureEntry;

        private QueuingEventSink eventSink = new QueuingEventSink();

        private final EventChannel eventChannel;

        private boolean isInitialized = false;

        private VideoPlayer(Context context, EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry, String dataSource, Result result) {
            this.eventChannel = eventChannel;
            this.textureEntry = textureEntry;
            mediaPlayer = new IjkExoMediaPlayer(context);
            Uri uri = Uri.parse(dataSource);
            mediaPlayer.setDataSource(context, uri);
            setupVideoPlayer(eventChannel, textureEntry, result);
        }

        private void setupVideoPlayer(EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry, Result result) {
            eventChannel.setStreamHandler(
                    new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object o, EventChannel.EventSink sink) {
                            eventSink.setDelegate(sink);
                        }

                        @Override
                        public void onCancel(Object o) {
                            eventSink.setDelegate(null);
                        }
                    });
            surface = new Surface(textureEntry.surfaceTexture());
            mediaPlayer.setSurface(surface);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setOnBufferingUpdateListener(new IMediaPlayer.OnBufferingUpdateListener() {
                @Override
                public void onBufferingUpdate(IMediaPlayer iMediaPlayer, int i) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", "bufferingUpdate");
                    List<Integer> range = Arrays.asList(0, mediaPlayer.getBufferedPercentage());
                    event.put("values", Collections.singletonList(range));
                    eventSink.success(event);
                }
            });

            mediaPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(IMediaPlayer iMediaPlayer) {
                    if (!isInitialized) {
                        isInitialized = true;
                        sendInitialized();
                    }
                }
            });

            mediaPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(IMediaPlayer iMediaPlayer, int i, int i1) {
                    if (eventSink != null) {
                        eventSink.error("VideoError", "Video player had error " + i, i1);
                    }
                    return true;
                }
            });
            mediaPlayer.prepareAsync();
            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", textureEntry.id());
            result.success(reply);
        }

        void play() {
            mediaPlayer.start();
        }

        void pause() {
            mediaPlayer.pause();
        }

        void setLooping(boolean value) {
            mediaPlayer.setLooping(value);
        }

        void setVolume(double value) {
            float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
            mediaPlayer.setVolume(bracketedValue, bracketedValue);
        }

        void seekTo(int location) {
            mediaPlayer.seekTo(location);
        }

        long getPosition() {
            return mediaPlayer.getCurrentPosition();
        }

        private void sendInitialized() {
            if (isInitialized) {
                Map<String, Object> event = new HashMap<>();
                event.put("event", "initialized");
                event.put("duration", mediaPlayer.getDuration());
                event.put("width", mediaPlayer.getVideoWidth());
                event.put("height", mediaPlayer.getVideoHeight());
                eventSink.success(event);
            }
        }

        void dispose() {
            if (isInitialized) {
                mediaPlayer.stop();
            }
            textureEntry.release();
            eventChannel.setStreamHandler(null);
            if (surface != null) {
                surface.release();
            }
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
        }
    }

    public static void registerWith(Registrar registrar) {
        final SuperPlayerPlugin plugin = new SuperPlayerPlugin(registrar);
        final MethodChannel channel = new MethodChannel(registrar.messenger(), "flutter.io/videoPlayer");
        channel.setMethodCallHandler(plugin);
        registrar.addViewDestroyListener(new PluginRegistry.ViewDestroyListener() {
            @Override
            public boolean onViewDestroy(FlutterNativeView view) {
                plugin.onDestroy();
                // We are not interested in assuming ownership of the NativeView.
                return false;
            }
        });
    }

    private SuperPlayerPlugin(Registrar registrar) {
        this.registrar = registrar;
        this.videoPlayers = new HashMap<>();
    }

    private final Map<Long, VideoPlayer> videoPlayers;

    private final Registrar registrar;

    void onDestroy() {
        // The whole FlutterView is being destroyed. Here we release resources acquired for all instances
        // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
        // be replaced with just asserting that videoPlayers.isEmpty().
        // https://github.com/flutter/flutter/issues/20989 tracks this.
        for (VideoPlayer player : videoPlayers.values()) {
            player.dispose();
        }
        videoPlayers.clear();
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        TextureRegistry textures = registrar.textures();
        if (textures == null) {
            result.error("no_activity", "video_player plugin requires a foreground activity", null);
            return;
        }
        switch (call.method) {
            case "init":
                for (VideoPlayer player : videoPlayers.values()) {
                    player.dispose();
                }
                videoPlayers.clear();
                break;
            case "create": {
                TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
                EventChannel eventChannel = new EventChannel(registrar.messenger(), "flutter.io/videoPlayer/videoEvents" + handle.id());
                VideoPlayer player;
                if (call.argument("asset") != null) {
                    String assetLookupKey;
                    if (call.argument("package") != null) {
                        assetLookupKey = registrar.lookupKeyForAsset((String) call.argument("asset"), (String) call.argument("package"));
                    } else {
                        assetLookupKey = registrar.lookupKeyForAsset((String) call.argument("asset"));
                    }
                    player = new VideoPlayer(registrar.context(), eventChannel, handle, "asset:///" + assetLookupKey, result);
                    videoPlayers.put(handle.id(), player);
                } else {
                    player = new VideoPlayer(registrar.context(), eventChannel, handle, (String) call.argument("uri"), result);
                    videoPlayers.put(handle.id(), player);
                }
                break;
            }
            default: {
                long textureId = ((Number) call.argument("textureId")).longValue();
                VideoPlayer player = videoPlayers.get(textureId);
                if (player == null) {
                    result.error("Unknown textureId", "No video player associated with texture id " + textureId, null);
                    return;
                }
                onMethodCall(call, result, textureId, player);
                break;
            }
        }
    }

    private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
        switch (call.method) {
            case "setLooping":
                player.setLooping((Boolean) call.argument("looping"));
                result.success(null);
                break;
            case "setVolume":
                player.setVolume((Double) call.argument("volume"));
                result.success(null);
                break;
            case "play":
                player.play();
                result.success(null);
                break;
            case "pause":
                player.pause();
                result.success(null);
                break;
            case "seekTo":
                int location = ((Number) call.argument("location")).intValue();
                player.seekTo(location);
                result.success(null);
                break;
            case "position":
                result.success(player.getPosition());
                break;
            case "dispose":
                player.dispose();
                videoPlayers.remove(textureId);
                result.success(null);
                break;
            default:
                result.notImplemented();
                break;
        }
    }
}
