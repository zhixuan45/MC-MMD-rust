package com.shiroha.mmdskin.stage.client.camera;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** 负责舞台模式音频的解码、加载与 OpenAL 播放控制。 */
public class StageAudioPlayer {
    private static final Logger logger = LogManager.getLogger();

    private int alBuffer = 0;
    private int alSource = 0;
    private boolean initialized = false;

    private String audioPath = null;
    private float durationSeconds = 0.0f;

    private float volume = 1.0f;
    private float syncGain = 1.0f;
    private final StageAudioSync sync = new StageAudioSync();

    private StageAudioLoadTask loading;

    /** Starts disk I/O and conversion off the render thread. */
    public void loadAsync(String path) {
        cleanup();
        loading = StageAudioLoadTask.takePrepared(path);
        if (loading == null) loading = StageAudioLoadTask.start(path);
        audioPath = path;
    }

    public StageAudioLoadTask loadingTask() { return loading; }

    /** Called on the client thread with its current OpenAL context. */
    public boolean finishLoading() {
        if (loading == null || !loading.isDone()) return false;
        StageAudioLoadTask task = loading;
        loading = null;
        ByteBuffer data = null;
        try {
            StagePcmDecoder.Pcm pcm = task.result();
            if (!AL.getCapabilities().AL_EXT_FLOAT32) {
                throw new IllegalStateException("The audio device does not support 32-bit float audio (AL_EXT_FLOAT32)");
            }
            data = MemoryUtil.memAlloc(pcm.data().length);
            data.put(pcm.data()).flip();
            alBuffer = AL10.alGenBuffers();
            int format = pcm.channels() == 1 ? EXTFloat32.AL_FORMAT_MONO_FLOAT32 : EXTFloat32.AL_FORMAT_STEREO_FLOAT32;
            AL10.alBufferData(alBuffer, format, data, pcm.sampleRate());
            checkAlError("float32 buffer upload");
            alSource = AL10.alGenSources();
            AL10.alSourcei(alSource, AL10.AL_BUFFER, alBuffer);
            AL10.alSourcef(alSource, AL10.AL_GAIN, volume);
            AL10.alSourcei(alSource, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(alSource, AL10.AL_POSITION, 0, 0, 0);
            checkAlError("source creation");
            durationSeconds = (float) (pcm.data().length / (double) (pcm.channels() * 4 * (long) pcm.sampleRate()));
            initialized = true;
            task.uploaded();
            return true;
        } catch (Exception e) {
            logger.error("[StageAudio] 32-bit audio load failed: {}", audioPath, e);
            cleanup();
            return false;
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }

    private static void checkAlError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) throw new IllegalStateException(operation + ": OpenAL error " + error);
    }

    public void play() { playFrom(0); }

    public void playFrom(float seconds) {
        if (!initialized || !Float.isFinite(seconds) || seconds >= durationSeconds) return;
        sync.reset();
        syncGain = 1.0f;
        AL10.alSourcef(alSource, AL10.AL_GAIN, volume);
        AL10.alSourcef(alSource, AL10.AL_PITCH, 1.0f);
        AL10.alSourceRewind(alSource);
        if (seconds > 0) setPlaybackPosition(seconds);
        AL10.alSourcePlay(alSource);
    }
    
    public void stop() {
        if (!initialized) return;
        AL10.alSourceStop(alSource);
    }
    
    public void pause() {
        if (!initialized) return;
        AL10.alSourcePause(alSource);
    }
    
    public void resume() {
        if (!initialized) return;
        int state = AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PAUSED) {
            AL10.alSourcePlay(alSource);
        }
    }
    
    public boolean isPlaying() {
        if (!initialized) return false;
        return AL10.alGetSourcei(alSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
    }
    
    public float getPlaybackPosition() {
        if (!initialized) return 0.0f;
        return AL10.alGetSourcef(alSource, AL11.AL_SEC_OFFSET);
    }
    
    public void setPlaybackPosition(float seconds) {
        if (!initialized || !Float.isFinite(seconds)) return;
        seconds = Math.max(0, Math.min(seconds, durationSeconds));
        AL10.alSourcef(alSource, AL11.AL_SEC_OFFSET, seconds);
    }
    
    public void setVolume(float vol) {
        this.volume = Float.isFinite(vol) ? Math.max(0.0f, Math.min(1.0f, vol)) : 1.0f;
        if (initialized) {
            AL10.alSourcef(alSource, AL10.AL_GAIN, this.volume * syncGain);
        }
    }

    public void synchronize(float seconds) {
        if (!initialized || !Float.isFinite(seconds)) return;
        // A shorter soundtrack ends once. Never repeatedly seek/restart it at its end.
        if (seconds >= durationSeconds) {
            sync.reset();
            return;
        }
        StageAudioSync.Adjustment adjustment = sync.update(Math.max(0, seconds), getPlaybackPosition(), System.nanoTime());
        syncGain = adjustment.gain();
        AL10.alSourcef(alSource, AL10.AL_GAIN, volume * syncGain);
        AL10.alSourcef(alSource, AL10.AL_PITCH, adjustment.pitch());
        if (Float.isFinite(adjustment.seekSeconds())) setPlaybackPosition(adjustment.seekSeconds());
    }
    
    public float getVolume() {
        return volume;
    }
    
    public float getDuration() {
        return durationSeconds;
    }
    
    public boolean isLoaded() {
        return initialized;
    }
    
    public String getAudioPath() {
        return audioPath;
    }
    
    public void cleanup() {
        if (loading != null) { loading.cancel(); loading = null; }
        sync.reset();
        syncGain = 1.0f;
        if (alSource != 0) {
            AL10.alSourceStop(alSource);
            AL10.alDeleteSources(alSource);
            alSource = 0;
        }
        if (alBuffer != 0) {
            AL10.alDeleteBuffers(alBuffer);
            alBuffer = 0;
        }
        initialized = false;
        audioPath = null;
        durationSeconds = 0.0f;
    }
    
}
