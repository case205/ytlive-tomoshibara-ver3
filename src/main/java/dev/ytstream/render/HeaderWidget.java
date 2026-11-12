package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * LIVEバッジ(ゆっくり脈動)、サーバー名、右側に情報列(header.columns の順):
 * online(人数+ゲージ、滑らかに変化) / viewers(YouTube同時視聴者) / uptime / tps / clock。
 */
final class HeaderWidget implements Widget {
    private final List<String> columns;
    private final DateTimeFormatter clock;
    private double shown = -1;
    private long lastNow = 0;

    HeaderWidget(Settings s) {
        this.columns = s.header().columns();
        ZoneId zone;
        try {
            zone = s.header().timezone().isBlank() ? ZoneId.systemDefault() : ZoneId.of(s.header().timezone());
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        DateTimeFormatter f;
        try {
            f = DateTimeFormatter.ofPattern(s.header().clockFormat());
        } catch (IllegalArgumentException e) {
            f = DateTimeFormatter.ofPattern("HH:mm");
        }
        this.clock = f.withZone(zone);
    }

    @Override
    public String region() {
        return "header";
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        return shown >= 0 && Math.abs(shown - m.players().size()) > 0.02;
    }

    @Override
    public int tickHz() {
        return 4; // LIVEの脈動・稼働時間・時計
    }

    @Override
    public void renderStatic(RenderContext c) {
        Draw.staticPanel(c, c.rect("header"), true);
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        ServerModel m = c.model;
        Theme t = c.theme;
        double target = m.players().size();
        if (shown < 0) {
            shown = target;
        } else {
            double dt = Math.min(200, Math.max(0, c.now - lastNow));
            shown += (target - shown) * (1 - Math.exp(-dt / 140.0));
            if (Math.abs(target - shown) < 0.02) shown = target;
        }
        lastNow = c.now;

        Rectangle r = c.rect("header");
        int pad = (int) (r.height * 0.16);

        // LIVE バッジ(点がゆっくり脈動)
        String liveLabel = c.s.label("live");
        Font fLive = c.fontPx(Font.BOLD, r.height * 0.30);
        FontMetrics fmLive = g.getFontMetrics(fLive);
        int tw = fmLive.stringWidth(liveLabel);
        int bh = (int) (r.height * 0.58);
        int dot = (int) (bh * 0.28);
        int bw = tw + dot + (int) (bh * 1.0);
        int bx = r.x + pad;
        int by = r.y + (r.height - bh) / 2;
        g.setColor(t.live());
        g.fillRoundRect(bx, by, bw, bh, bh, bh);
        double pulse = 0.5 + 0.5 * Math.cos(2 * Math.PI * (c.now % 1600) / 1600.0);
        int dx = bx + (int) (bh * 0.4);
        int dy = by + (bh - dot) / 2;
        int halo = (int) (dot * (0.6 + 0.6 * (1 - pulse)));
        g.setColor(new Color(255, 255, 255, (int) (70 * pulse)));
        g.fillOval(dx - halo / 2, dy - halo / 2, dot + halo, dot + halo);
        g.setColor(new Color(255, 255, 255, 140 + (int) (115 * pulse)));
        g.fillOval(dx, dy, dot, dot);
        g.setFont(fLive);
        g.setColor(Color.WHITE);
        g.drawString(liveLabel, dx + dot + (int) (bh * 0.2), by + (bh + fmLive.getAscent() - fmLive.getDescent()) / 2);

        // 右側の列(右端から詰めて配置)
        List<String> cols = new ArrayList<>();
        for (String col : columns) {
            if (col.equals("viewers") && m.viewers() < 0) continue; // 未連携・取得前は出さない
            if (col.equals("tps") && m.tps() < 0) continue;
            cols.add(col);
        }
        Font fLabel = c.fontPx(Font.PLAIN, r.height * 0.20);
        Font fValue = c.fontPx(Font.BOLD, r.height * 0.32);
        FontMetrics fmL = g.getFontMetrics(fLabel);
        FontMetrics fmV = g.getFontMetrics(fValue);
        int top = r.y + (int) (r.height * 0.12);
        int labelBase = top + fmL.getAscent();
        int valueBase = labelBase + fmL.getDescent() + fmV.getAscent();

        int x = r.x + r.width - pad;
        int leftmost = x;
        for (int i = cols.size() - 1; i >= 0; i--) {
            String col = cols.get(i);
            int cw = (int) (r.width * (col.equals("online") ? 0.17 : col.equals("uptime") ? 0.15 : 0.11));
            x -= cw;
            column(c, col, x, cw, labelBase, valueBase, fLabel, fValue, r);
            leftmost = x;
            x -= pad;
        }

        // サーバー名
        Font fName = c.fontPx(Font.BOLD, r.height * 0.40);
        FontMetrics fmName = g.getFontMetrics(fName);
        int nx = bx + bw + pad;
        int nameMax = leftmost - pad - nx;
        g.setFont(fName);
        String name = TextUtil.ellipsize(fmName, c.s.serverName(), nameMax);
        Draw.text(c, name, nx, r.y + (r.height + fmName.getAscent() - fmName.getDescent()) / 2, t.text());
    }

    private void column(RenderContext c, String col, int x, int w, int labelBase, int valueBase,
                        Font fLabel, Font fValue, Rectangle r) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        ServerModel m = c.model;
        String label;
        String value;
        Color vc = t.text();
        switch (col) {
            case "online" -> {
                label = c.s.label("online");
                int max = Math.max(1, m.maxPlayers());
                value = Math.round(shown) + " / " + max;
                int gh = Math.max(4, (int) (r.height * 0.07));
                int gy = r.y + r.height - (int) (r.height * 0.12) - gh;
                g.setColor(t.track());
                g.fillRoundRect(x, gy, w, gh, gh, gh);
                int fill = (int) Math.round(w * Math.min(1.0, shown / max));
                if (fill > 0) {
                    Color a = t.accent();
                    g.setPaint(new GradientPaint(x, 0, a.darker(), x + w, 0, a));
                    g.fillRoundRect(x, gy, Math.max(fill, gh), gh, gh, gh);
                }
            }
            case "viewers" -> {
                label = c.s.label("viewers");
                value = formatCount(m.viewers());
            }
            case "tps" -> {
                label = c.s.label("tps");
                double tps = m.tps();
                value = String.format(java.util.Locale.ROOT, "%.1f", tps);
                vc = tps >= 18 ? t.join() : tps >= 15 ? new Color(240, 200, 60) : t.leave();
            }
            case "clock" -> {
                label = c.s.label("clock");
                value = clock.format(Instant.ofEpochMilli(c.now));
            }
            default -> {
                label = c.s.label("uptime");
                value = formatUptime(c.uptimeSec);
            }
        }
        g.setFont(fLabel);
        Draw.text(c, TextUtil.ellipsize(g.getFontMetrics(fLabel), label, w), x, labelBase, t.dim());
        g.setFont(fValue);
        Draw.text(c, TextUtil.ellipsize(g.getFontMetrics(fValue), value, w), x, valueBase, vc);
    }

    static String formatCount(long n) {
        if (n < 0) return "-";
        if (n < 10_000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(java.util.Locale.ROOT, "%.1f万", n / 10_000.0);
        return String.format(java.util.Locale.ROOT, "%.0f万", n / 10_000.0);
    }

    static String formatUptime(int sec) {
        int d = sec / 86400;
        int h = (sec % 86400) / 3600;
        int mi = (sec % 3600) / 60;
        int s = sec % 60;
        return d > 0 ? String.format("%dd %02d:%02d:%02d", d, h, mi, s) : String.format("%02d:%02d:%02d", h, mi, s);
    }
}
