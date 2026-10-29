package com.shiroha.mmdskin.stage.client.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class StageAudioLoadTaskTest {
    @TempDir Path temp;
    @Test void hostPreloadIsConsumedOnceAndUploadsOnlyAfterBackgroundProcessing() throws Exception {
        Path file=temp.resolve("stereo.wav");
        byte[] data=new byte[44100*4];
        try (var input=new AudioInputStream(new ByteArrayInputStream(data),new AudioFormat(44100,16,2,true,false),44100)) {
            AudioSystem.write(input,AudioFileFormat.Type.WAVE,file.toFile());
        }
        StageAudioLoadTask task=StageAudioLoadTask.prepare(file.toString());
        var pcm=assertTimeoutPreemptively(Duration.ofSeconds(5),task::result);
        assertEquals(data.length*2,pcm.data().length);
        assertEquals(2,pcm.channels());
        assertEquals(.95f,task.progress());
        assertSame(task,StageAudioLoadTask.takePrepared(file.toString()));
        assertNull(StageAudioLoadTask.takePrepared(file.toString()));
        task.uploaded();
        assertEquals(1,task.progress());
    }
    @Test void cancelledPreloadCannotBeUsedByTheNextStage() {
        StageAudioLoadTask task=StageAudioLoadTask.prepare(temp.resolve("missing.wav").toString());
        task.cancel();
        assertNull(StageAudioLoadTask.takePrepared(temp.resolve("missing.wav").toString()));
    }
}
