package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.model.ServerModel.Toast;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.List;

/** 参加/退出/実績/死亡の通知。左からスライドイン → 一定時間表示 → スライドアウトして自動削除。 */
final class ToastWidget implements Widget {
    private static final long IN_MS = 350;
    private static final long OUT_MS = 400;
    private static final Color GOLD = new Color(255, 200, 60);
    private static final Color GRAY = new Color(170, 170, 178);
    private final long lifeMs;
    private final long noticeMs;

    ToastWidget(Settings s) {
        this.lifeMs = Math.max(2000, s.toastLifetimeSec() * 1000L);
        this.noticeMs = Math.max(3000, s.noticeLifetimeSec() * 1000L);
    }

    private long life(Toast t) {
        return t.kind() == ServerModel.ToastKind.NOTICE ? noticeMs : lifeMs;
    }

    @Override
    public String region() {
        return "toasts";
    }

    /** 画面左端からスライドインするため、左端までを描画範囲に含める。 */
    @Override
    public Rectangle area(RenderContext c) {
        Rectangle r = c.rect("toasts");
        int m = c.px(6);
        return new Rectangle(0, r.y - m, r.x + r.width + m, r.height + 2 * m);
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        for (Toast t : m.toasts()) {
            long age = now - t.timeMs();
            long life = life(t);
            if (age < IN_MS + 60 || (age > life - OUT_MS - 60 && age < life + 60)) return true;
        }
        return false;
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("toasts");
        int h = c.px(44);
        int gap = c.px(8);
        int maxCount = Math.max(1, r.height / (h + gap));

        List<Toast> all = c.model.toasts();
        Font f = c.font(Font.BOLD, 19);
        FontMetrics fm = g.getFontMetrics(f);
        g.setFont(f);

        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < maxCount; i--) {
            Toast toast = all.get(i);
            long age = c.now - toast.timeMs();
            long life = life(toast);
            if (age >= life || age < 0) continue;

            String text;
            Color bar;
            switch (toast.kind()) {
                case JOIN -> {
                    text = c.s.label("joined").replace("{name}", toast.name());
                    bar = t.join();
                }
                case LEAVE -> {
                    text = c.s.label("left").replace("{name}", toast.name());
                    bar = t.leave();
                }
                case ADVANCEMENT -> {
                    text = "\u2605 " + toast.text();
                    bar = GOLD;
                }
                case NOTICE -> {
                    text = "\u25C6 " + toast.text();
                    bar = t.accent();
                }
                default -> {
                    text = "\u2620 " + toast.text();
                    bar = GRAY;
                }
            }
            int maxTextW = r.width - c.px(54);
            text = TextUtil.ellipsize(fm, text, maxTextW);
            int w = Math.min(r.width, fm.stringWidth(text) + c.px(54));
            double off = 0;
            if (age < IN_MS) {
                off = -(1 - RenderContext.easeOutCubic(age / (double) IN_MS)) * (w + c.px(24));
            } else if (age > life - OUT_MS) {
                off = -RenderContext.easeOutCubic((age - (life - OUT_MS)) / (double) OUT_MS) * (r.x + w + c.px(24));
            }
            if (age < IN_MS) off *= (r.x + w + c.px(24)) / (double) (w + c.px(24)); // 画面の外から入ってくる
            int x = r.x + (int) Math.round(off);
            int y = r.y + shown * (h + gap);
            Draw.panel(c, new Rectangle(x, y, w, h), Math.min(c.arc(), h / 2));
            g.setColor(bar);
            g.fillRoundRect(x + c.px(6), y + c.px(8), c.px(6), h - c.px(16), c.px(6), c.px(6));
            g.setFont(f);
            Draw.text(c, text, x + c.px(24), y + (h + fm.getAscent() - fm.getDescent()) / 2, t.text());
            shown++;
        }
    }
}
