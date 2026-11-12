package dev.ytstream;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.render.FrameRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RendererSmokeTest {
    @Test
    void rendersFrameAndSkipsWhenStatic(@TempDir Path dir) {
        System.setProperty("java.awt.headless", "true");
        Settings s = Settings.defaults();
        ServerModel m = new ServerModel();
        m.setMaxPlayers(50);
        m.playerJoined(new ServerModel.PlayerEntry(UUID.randomUUID(), "Steve", false));
        long now = 1_000_000_000L;
        m.addChat("Steve", "hello", now - 60_000);
        try (FrameRenderer r = new FrameRenderer(s, m, dir, now - 5000)) {
            assertEquals(s.width() * s.height() * 3, r.data().length);
            assertTrue(r.render(now, false), "最初のフレームは描画される");
            byte[] first = r.data().clone();
            assertFalse(r.render(now + 20, false), "状態不変・アニメ無しなら再描画しない");
            assertArrayEquals(first, r.data());
            m.addChat("Alex", "new message", now + 30);
            assertTrue(r.render(now + 40, false), "状態変化で再描画される");
            assertFalse(java.util.Arrays.equals(first, r.data()));
        }
    }

    /** 部分再描画の結果が、同じ時刻のフル描画と1ピクセル単位で一致すること。 */
    @Test
    void partialRedrawMatchesFullRedraw(@TempDir Path dir) {
        System.setProperty("java.awt.headless", "true");
        Settings s = Settings.defaults();
        ServerModel m = new ServerModel();
        m.playerJoined(new ServerModel.PlayerEntry(UUID.randomUUID(), "Steve", false));
        long now = 2_000_000_000L;
        try (FrameRenderer partial = new FrameRenderer(s, m, dir, now - 5000);
             FrameRenderer full = new FrameRenderer(s, m, dir, now - 5000)) {
            partial.render(now, true);
            full.render(now, true);
            m.addChat("Alex", "hello", now + 10);
            partial.render(now + 20, false); // 状態変化(フル)
            long t = now + 1300;             // ヘッダーの時刻更新のみ → 部分再描画
            long before = partial.partialRedraws();
            assertTrue(partial.render(t, false));
            assertTrue(partial.partialRedraws() > before, "時刻だけの変化は部分再描画で処理される");
            full.render(now + 20, true);
            full.render(t, true);
            assertArrayEquals(full.data(), partial.data(), "部分再描画とフル描画の結果が一致しない");
        }
    }

    @Test
    void pingBarsAreBucketed() {
        assertEquals(-1, ServerModel.pingBars(-1));
        assertEquals(4, ServerModel.pingBars(20));
        assertEquals(3, ServerModel.pingBars(100));
        assertEquals(2, ServerModel.pingBars(200));
        assertEquals(1, ServerModel.pingBars(400));
        assertEquals(0, ServerModel.pingBars(900));
    }
}
