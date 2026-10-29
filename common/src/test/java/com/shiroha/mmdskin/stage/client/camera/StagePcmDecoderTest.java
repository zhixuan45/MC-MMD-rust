package com.shiroha.mmdskin.stage.client.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StagePcmDecoderTest {
    @TempDir Path temp;

    @Test void floatWavPreservesPeaksAndStereoWithoutSixteenBitQuantization() throws Exception {
        float[] samples = {0, .5f, .99f, 1, 1.05f, 1.5f, .99f, -.5f, -1, -1.05f, -1.5f, 0};
        ByteBuffer wave = ByteBuffer.allocate(44 + samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(wave.capacity() - 8);
        wave.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16);
        wave.putShort((short) 3).putShort((short) 2).putInt(44100).putInt(44100 * 8).putShort((short) 8).putShort((short) 32);
        wave.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(samples.length * 4);
        for (float sample : samples) wave.putFloat(sample);
        Path path = temp.resolve("float-stereo.wav");
        Files.write(path, wave.array());
        StagePcmDecoder.Pcm pcm = StagePcmDecoder.decodeWav(path.toFile());
        assertEquals(44100, pcm.sampleRate());
        assertEquals(2, pcm.channels());
        assertArrayEquals(samples, floats(pcm));
        assertEquals(samples.length * 4, pcm.data().length);
    }

    @Test void signedAndUnsignedEightBitSilenceAreBothZero() throws Exception {
        assertArrayEquals(new float[]{-1, 0, 127/128f}, decode(new byte[]{-128,0,127}, AudioFormat.Encoding.PCM_SIGNED, 8, false));
        assertArrayEquals(new float[]{-1, 0, 127/128f}, decode(new byte[]{0,-128,-1}, AudioFormat.Encoding.PCM_UNSIGNED, 8, false));
    }

    @Test void integerWidthsAndEndiannessPreservePolarityAndAmplitude() throws Exception {
        for (boolean big : new boolean[]{false,true}) {
            for (int bits : new int[]{16,24,32}) {
                ByteBuffer raw = ByteBuffer.allocate(3 * bits / 8).order(big ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                long[] values = {-(1L << (bits-1)), 0, (1L << (bits-1)) - 1};
                for (long value : values) for (int i=0;i<bits/8;i++) {
                    int shift = (big ? bits/8-1-i : i) * 8;
                    raw.put((byte) (value >> shift));
                }
                assertArrayEquals(new float[]{-1,0,(float)(values[2] / (double)(1L << (bits-1)))}, decode(raw.array(),AudioFormat.Encoding.PCM_SIGNED,bits,big));
            }
        }
    }

    @Test void nonFiniteFloatSamplesNeverReachTheAudioDevice() throws Exception {
        ByteBuffer raw = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,0}) raw.putDouble(value);
        assertArrayEquals(new float[]{0,0,0,0},decode(raw.array(),AudioFormat.Encoding.PCM_FLOAT,64,true));
    }

    @Test void rejectsUnsupportedChannelCount() {
        AudioFormat format = new AudioFormat(44100,16,6,true,false);
        assertThrows(java.io.IOException.class, () -> StagePcmDecoder.decode(new AudioInputStream(new ByteArrayInputStream(new byte[12]),format,1)));
    }

    private static float[] decode(byte[] raw, AudioFormat.Encoding encoding, int bits, boolean big) throws Exception {
        AudioFormat format = new AudioFormat(encoding,44100,bits,1,bits/8,44100,big);
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(raw),format,raw.length / (bits/8))) {
            return floats(StagePcmDecoder.decode(input));
        }
    }
    private static float[] floats(StagePcmDecoder.Pcm pcm) {
        var buffer=ByteBuffer.wrap(pcm.data()).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] result=new float[buffer.remaining()]; buffer.get(result); return result;
    }
}
