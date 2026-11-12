package dev.ytstream.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BgmTest {
    @Test
    void scanListsOnlyAudioFilesSorted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.mp3"), "x");
        Files.writeString(dir.resolve("A.ogg"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve("empty.wav"), "");
        List<Path> tracks = BgmLibrary.scan(dir);
        assertEquals(2, tracks.size());
        assertEquals("A", BgmLibrary.title(tracks.get(0)));
        assertEquals("b", BgmLibrary.title(tracks.get(1)));
    }

    @Test
    void scanMissingDirIsEmpty(@TempDir Path dir) {
        assertTrue(BgmLibrary.scan(dir.resolve("none")).isEmpty());
    }

    @Test
    void gainScalesAndClips() {
        // サンプル値 1000 と -20000(16bit LE)
        byte[] b = {(byte) 0xE8, 0x03, (byte) 0xE0, (byte) 0xB1};
        BgmFeeder.applyGain(b, 4, 0.5f, 0.5f);
        assertEquals(500, (short) ((b[0] & 0xFF) | (b[1] << 8)));
        assertEquals(-10000, (short) ((b[2] & 0xFF) | (b[3] << 8)));

        byte[] loud = {(byte) 0xFF, 0x7F}; // 32767
        BgmFeeder.applyGain(loud, 2, 2f, 2f);
        assertEquals(32767, (short) ((loud[0] & 0xFF) | (loud[1] << 8)));
    }

    @Test
    void controlVolumeIsClampedAndMuteSilences() {
        BgmControl c = new BgmControl();
        c.setVolume(500);
        assertEquals(100, c.volume());
        assertEquals(1f, c.gain(), 1e-6);
        c.setMuted(true);
        assertEquals(0f, c.gain());
    }
}
