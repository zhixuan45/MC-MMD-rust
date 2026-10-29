package com.shiroha.mmdskin.stage.client.camera;

import javazoom.jl.decoder.*;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/** Background decoding only. OpenAL allocation/upload always stays on the client thread. */
public final class StageAudioLoadTask {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mmd-stage-audio-loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<StageAudioLoadTask> PREPARED = new AtomicReference<>();
    private final String path;
    private final Future<StagePcmDecoder.Pcm> future;
    private volatile float progress;

    private StageAudioLoadTask(String path) {
        this.path = path;
        future = WORKER.submit(() -> {
            StagePcmDecoder.Pcm result = decode(new File(path), value -> progress = (float) Math.min(.95, value * .95));
            progress = .95f;
            return result;
        });
    }

    public static StageAudioLoadTask start(String path) { return new StageAudioLoadTask(path); }

    /** The host preloads before broadcasting a stage start, so everyone starts at frame zero. */
    public static StageAudioLoadTask prepare(String path) {
        StageAudioLoadTask next = start(path);
        StageAudioLoadTask previous = PREPARED.getAndSet(next);
        if (previous != null) previous.cancel();
        return next;
    }

    static StageAudioLoadTask takePrepared(String path) {
        StageAudioLoadTask task = PREPARED.get();
        return task != null && task.path.equals(path) && task.isDone() && PREPARED.compareAndSet(task, null) ? task : null;
    }

    public float progress() { return progress; }
    public boolean isDone() { return future.isDone(); }
    public String failure() {
        if (!isDone()) return null;
        try { future.get(); return null; }
        catch (Exception e) { return e.getCause() != null ? e.getCause().toString() : e.toString(); }
    }
    public void cancel() {
        PREPARED.compareAndSet(this, null);
        future.cancel(true);
    }

    StagePcmDecoder.Pcm result() throws Exception { return future.get(); }
    void uploaded() { progress = 1; }

    private static StagePcmDecoder.Pcm decode(File file, DoubleConsumer progress) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav")) return StagePcmDecoder.decodeWav(file, progress);
        if (name.endsWith(".mp3")) return decodeMp3(file, progress);
        if (name.endsWith(".ogg")) return decodeOgg(file, progress);
        throw new IOException("Unsupported stage audio: " + file.getName());
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio load cancelled");
    }

    private static StagePcmDecoder.Pcm decodeMp3(File file, DoubleConsumer progress) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int rate = 0, channels = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            Bitstream stream = new Bitstream(input);
            Decoder decoder = new Decoder();
            try {
                Header header;
                ByteBuffer frame = ByteBuffer.allocate(1152 * 2 * 4).order(ByteOrder.nativeOrder());
                while ((header = stream.readFrame()) != null) {
                    checkCancelled();
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, stream);
                    if (rate == 0) { rate = output.getSampleFrequency(); channels = output.getChannelCount(); }
                    if (rate != output.getSampleFrequency() || channels != output.getChannelCount()) {
                        throw new IOException("MP3 format changes mid-stream");
                    }
                    frame.clear();
                    for (int i = 0; i < output.getBufferLength(); i++) frame.putFloat(output.getBuffer()[i] / 32768.0f);
                    pcm.write(frame.array(), 0, frame.position());
                    stream.closeFrame();
                    progress.accept(Math.min(1, input.getChannel().position() / (double) Math.max(1, file.length())));
                }
            } finally { stream.close(); }
        }
        if (pcm.size() == 0 || rate <= 0 || (channels != 1 && channels != 2)) throw new IOException("Empty MP3");
        return new StagePcmDecoder.Pcm(pcm.toByteArray(), rate, channels);
    }

    private static StagePcmDecoder.Pcm decodeOgg(File file, DoubleConsumer progress) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        checkCancelled();
        progress.accept(.2);
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        encoded.put(bytes).flip();
        long handle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.mallocInt(1);
            handle = STBVorbis.stb_vorbis_open_memory(encoded, error, null);
            if (handle == 0) throw new IOException("Vorbis decoder error " + error.get(0));
            STBVorbisInfo info = STBVorbis.stb_vorbis_get_info(handle, STBVorbisInfo.malloc(stack));
            int channels = info.channels(), rate = info.sample_rate();
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(handle);
            if ((channels != 1 && channels != 2) || rate <= 0 || frames <= 0) throw new IOException("Invalid OGG format");
            ByteBuffer pcm = ByteBuffer.allocate(Math.multiplyExact(frames, channels * 4)).order(ByteOrder.nativeOrder());
            float[] chunk = new float[4096 * channels];
            int count;
            while ((count = STBVorbis.stb_vorbis_get_samples_float_interleaved(handle, channels, chunk)) > 0) {
                checkCancelled();
                for (int i = 0; i < count * channels; i++) pcm.putFloat(Float.isFinite(chunk[i]) ? chunk[i] : 0);
                progress.accept(.2 + .8 * pcm.position() / pcm.capacity());
            }
            if (pcm.position() == 0) throw new IOException("Empty OGG");
            return new StagePcmDecoder.Pcm(java.util.Arrays.copyOf(pcm.array(), pcm.position()), rate, channels);
        } finally {
            if (handle != 0) STBVorbis.stb_vorbis_close(handle);
            MemoryUtil.memFree(encoded);
        }
    }
}
