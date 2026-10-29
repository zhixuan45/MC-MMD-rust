package com.shiroha.mmdskin.stage.client.camera;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.DoubleConsumer;

/** Converts WAV samples to native-endian float32; never quantizes float WAVs to PCM16. */
final class StagePcmDecoder {
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
                || rate > Integer.MAX_VALUE || rate != (int) rate
                || !(floating ? bits == 32 || bits == 64
                             : (signed || unsigned) && (bits == 8 || bits == 16 || bits == 24 || bits == 32))) {
            throw new IOException("Unsupported WAV format: " + format);
        }
        int sampleBytes = bits / 8;
        int stride = format.getFrameSize();
        if (stride < channels * sampleBytes) throw new IOException("Invalid WAV frame size");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[Math.max(stride, 65536 / stride * stride)];
        long expected = input.getFrameLength() > 0 ? input.getFrameLength() * stride : 0;
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Audio load cancelled");
            bytes.write(chunk, 0, count);
            progress.accept(expected > 0 ? Math.min(.4, .4 * bytes.size() / expected) : .1);
        }
        byte[] raw = bytes.toByteArray();
        if (raw.length == 0 || raw.length % stride != 0) throw new IOException("Empty or incomplete WAV frames");
        ByteBuffer source = ByteBuffer.wrap(raw).order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        ByteBuffer pcm = ByteBuffer.allocate(Math.multiplyExact(raw.length / stride, channels * 4))
                .order(ByteOrder.nativeOrder());
        for (int frame = 0; frame < raw.length; frame += stride) {
            if ((frame / stride & 16383) == 0) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Audio load cancelled");
                progress.accept(.4 + .6 * frame / raw.length);
            }
            for (int channel = 0; channel < channels; channel++) {
                int offset = frame + channel * sampleBytes;
                float sample;
                if (floating) {
                    double value = bits == 32 ? source.getFloat(offset) : source.getDouble(offset);
                    // Preserve headroom above ±1; the audio mixer handles the final output.
                    // Non-finite/corrupted samples must not poison the entire mixer.
                    sample = Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE ? (float) value : 0;
                } else {
                    long value = 0;
                    for (int b = 0; b < sampleBytes; b++) {
                        int index = format.isBigEndian() ? offset + sampleBytes - 1 - b : offset + b;
                        value |= (raw[index] & 0xffL) << (b * 8);
                    }
                    long midpoint = 1L << (bits - 1);
                    if (unsigned) value -= midpoint;
                    else if ((value & midpoint) != 0) value -= 1L << bits;
                    sample = (float) (value / (double) midpoint);
                }
                pcm.putFloat(sample);
            }
        }
        progress.accept(1);
        return new Pcm(pcm.array(), (int) rate, channels);
    }
}
