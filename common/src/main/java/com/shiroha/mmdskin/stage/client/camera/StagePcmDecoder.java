package com.shiroha.mmdskin.stage.client.camera;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.DoubleConsumer;

/** Converts WAV samples to native-endian float32; never quantizes float WAVs to PCM16. */
final class StagePcmDecoder {
    static final int MAX_SOURCE_BYTES = 128 * 1024 * 1024;
    static final int MAX_PCM_BYTES = 128 * 1024 * 1024;
    private static final int MAX_SAMPLE_RATE = 192_000;
    private static final int MAX_DURATION_SECONDS = 15 * 60;
    record Pcm(byte[] data, int sampleRate, int channels) { }

    private StagePcmDecoder() { }

    static Pcm decodeWav(File file) throws Exception {
        return decodeWav(file, ignored -> { });
    }

    static Pcm decodeWav(File file, DoubleConsumer progress) throws Exception {
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file)) {
            return decode(input, progress);
        }
    }

    static Pcm decode(AudioInputStream input) throws IOException {
        return decode(input, ignored -> { });
    }

    static Pcm decode(AudioInputStream input, DoubleConsumer progress) throws IOException {
        AudioFormat format = input.getFormat();
        int channels = format.getChannels();
        float rate = format.getSampleRate();
        int bits = format.getSampleSizeInBits();
        boolean floating = AudioFormat.Encoding.PCM_FLOAT.equals(format.getEncoding());
        boolean signed = AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding());
        boolean unsigned = AudioFormat.Encoding.PCM_UNSIGNED.equals(format.getEncoding());
        if ((channels != 1 && channels != 2) || !Float.isFinite(rate) || rate <= 0
                || rate > MAX_SAMPLE_RATE || rate != (int) rate
                || !(floating ? bits == 32 || bits == 64
                             : (signed || unsigned) && (bits == 8 || bits == 16 || bits == 24 || bits == 32))) {
            throw new IOException("Unsupported WAV format: " + format);
        }
        int sampleBytes = bits / 8;
        int stride = format.getFrameSize();
        if (stride < channels * sampleBytes || stride > 64) throw new IOException("Invalid WAV frame size");
        long expected = input.getFrameLength();
        int frameBytes = channels * Float.BYTES;
        int budget = pcmBudget((int) rate, channels);
        if (expected > budget / frameBytes || expected > MAX_SOURCE_BYTES / stride)
            throw new IOException("Stage WAV exceeds the audio budget");
        var bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536 / stride * stride];
        ByteBuffer source = ByteBuffer.wrap(chunk).order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        ByteBuffer pcm = ByteBuffer.allocate(chunk.length / stride * frameBytes).order(ByteOrder.nativeOrder());
        long readBytes = 0;
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Audio load cancelled");
            readBytes += count;
            if (count % stride != 0 || readBytes > MAX_SOURCE_BYTES || count / stride > (budget - bytes.size()) / frameBytes) {
                throw new IOException("Invalid WAV frames or audio budget exceeded");
            }
            pcm.clear();
            for (int frame = 0; frame < count; frame += stride) {
                for (int channel = 0; channel < channels; channel++) {
                    int offset = frame + channel * sampleBytes;
                    float sample;
                    if (floating) {
                        double value = bits == 32 ? source.getFloat(offset) : source.getDouble(offset);
                        // Preserve finite headroom above ±1 for the mixer.
                        sample = Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE ? (float) value : 0;
                    } else {
                        long value = 0;
                        for (int b = 0; b < sampleBytes; b++) {
                            int index = format.isBigEndian() ? offset + sampleBytes - 1 - b : offset + b;
                            value |= (chunk[index] & 0xffL) << (b * 8);
                        }
                        long midpoint = 1L << (bits - 1);
                        if (unsigned) value -= midpoint;
                        else if ((value & midpoint) != 0) value -= 1L << bits;
                        sample = (float) (value / (double) midpoint);
                    }
                    pcm.putFloat(sample);
                }
            }
            bytes.write(pcm.array(), 0, pcm.position());
            progress.accept(expected > 0 ? Math.min(1, readBytes / (double) (expected * stride)) : .1);
        }
        if (bytes.size() == 0) throw new IOException("Empty WAV");
        progress.accept(1);
        return new Pcm(bytes.toByteArray(), (int) rate, channels);
    }

    static int pcmBudget(int rate, int channels) throws IOException {
        if (rate <= 0 || rate > MAX_SAMPLE_RATE || (channels != 1 && channels != 2)) {
            throw new IOException("Unsupported stage audio format");
        }
        return (int) Math.min(MAX_PCM_BYTES, (long) rate * channels * Float.BYTES * MAX_DURATION_SECONDS);
    }
}
