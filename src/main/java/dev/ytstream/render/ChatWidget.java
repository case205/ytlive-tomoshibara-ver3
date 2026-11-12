package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.model.ServerModel.ChatEntry;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** チャット欄。新着が下からスライドインし、既存の行が滑らかに押し上げられる。 */
final class ChatWidget implements Widget {
    private static final long IN_MS = 350;
    private static final long FADE_MS = 600;
    private static final Color YT_RED = new Color(230, 33, 23);
    private static final Color MOD_BLUE = new Color(94, 132, 241);
    private static final Color SUPER_GOLD = new Color(255, 202, 40);
    private final long lifetimeMs;
    private final boolean badges;

    ChatWidget(Settings s) {
        this.lifetimeMs = s.chatLifetimeSec() * 1000L;
        this.badges = s.visual().youtubeBadge();
    }

    @Override
    public String region() {
        return "chat";
    }

    @Override
    public void renderStatic(RenderContext c) {
        Rectangle r = c.rect("chat");
        Draw.staticPanel(c, r, false);
        int pad = c.px(14);
        Font fTitle = c.font(Font.BOLD, 21);
        FontMetrics fmT = c.g.getFontMetrics(fTitle);
        c.g.setFont(fTitle);
        Draw.text(c, c.s.label("chat"), r.x + pad, r.y + pad + fmT.getAscent(), c.theme.text());
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        if (now - m.lastChatMs() < IN_MS + 60) return true;
        if (lifetimeMs > 0) {
            for (ChatEntry e : m.chat()) {
                long age = now - e.timeMs();
                if (age > lifetimeMs - FADE_MS - 60 && age < lifetimeMs + 60) return true;
            }
        }
        return false;
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("chat");

        int pad = c.px(14);
        Font fTitle = c.font(Font.BOLD, 21);
        FontMetrics fmT = g.getFontMetrics(fTitle);

        int areaTop = r.y + pad + fmT.getHeight() + c.px(6);
        int areaBottom = r.y + r.height - pad;
        int areaLeft = r.x + pad;
        int areaW = r.width - 2 * pad;
        if (areaBottom <= areaTop) return;

        List<ChatEntry> visible = new ArrayList<>();
        for (ChatEntry e : c.model.chat()) {
            if (lifetimeMs == 0 || c.now - e.timeMs() < lifetimeMs) visible.add(e);
        }
        int from = Math.max(0, visible.size() - c.s.chatMaxLines());
        visible = visible.subList(from, visible.size());
        if (visible.isEmpty()) return;

        Font f = c.font(Font.PLAIN, 19);
        FontMetrics fm = g.getFontMetrics(f);
        g.setFont(f);
        int lh = fm.getHeight();
        int gap = c.px(6);

        Font fBadge = c.font(Font.BOLD, 13);
        List<List<String>> wrapped = new ArrayList<>(visible.size());
        List<Integer> indents = new ArrayList<>(visible.size());
        for (ChatEntry e : visible) {
            int indent = badgeWidth(c, e, fBadge);
            indents.add(indent);
            wrapped.add(TextUtil.wrap(fm, e.name() + ": " + e.text(), areaW - indent, 2));
        }

        int last = visible.size() - 1;
        ChatEntry newest = visible.get(last);
        double ease = RenderContext.easeOutCubic((c.now - newest.timeMs()) / (double) IN_MS);
        int newestH = wrapped.get(last).size() * lh + gap;
        double y = areaBottom + (1 - ease) * newestH;

        Shape oldClip = g.getClip();
        g.clipRect(areaLeft - 2, areaTop, areaW + 4, areaBottom - areaTop);
        for (int i = last; i >= 0; i--) {
            List<String> lines = wrapped.get(i);
            int h = lines.size() * lh + gap;
            y -= h;
            if (y + h < areaTop) break;
            ChatEntry e = visible.get(i);
            double alpha = i == last ? ease : 1.0;
            if (lifetimeMs > 0) {
                double remaining = lifetimeMs - (c.now - e.timeMs());
                if (remaining < FADE_MS) alpha *= Math.max(0, remaining / FADE_MS);
            }
            if (alpha <= 0.01) continue;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1.0, alpha)));
            String prefix = e.name() + ": ";
            int indent = indents.get(i);
            if (e.source() == ServerModel.ChatSource.YOUTUBE_SUPER) { // スーパーチャットは帯で強調
                g.setColor(new Color(SUPER_GOLD.getRed(), SUPER_GOLD.getGreen(), SUPER_GOLD.getBlue(), 48));
                g.fillRoundRect(areaLeft - c.px(4), (int) Math.round(y) - c.px(1), areaW + c.px(8), h - gap + c.px(2), c.px(8), c.px(8));
            }
            for (int j = 0; j < lines.size(); j++) {
                int base = (int) Math.round(y) + j * lh + fm.getAscent();
                String line = lines.get(j);
                int lx = areaLeft + indent;
                if (j == 0 && indent > 0) drawBadge(c, e, fBadge, areaLeft, base);
                g.setFont(f);
                if (j == 0 && line.startsWith(prefix)) {
                    Draw.text(c, prefix, lx, base, nameColor(c, e));
                    Draw.text(c, line.substring(prefix.length()), lx + fm.stringWidth(prefix), base, t.text());
                } else {
                    Draw.text(c, line, lx, base, t.text());
                }
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setClip(oldClip);
    }

    private boolean isYouTube(ChatEntry e) {
        return e.source() != ServerModel.ChatSource.GAME;
    }

    private int badgeWidth(RenderContext c, ChatEntry e, Font fBadge) {
        if (!badges || !isYouTube(e)) return 0;
        FontMetrics fm = c.g.getFontMetrics(fBadge);
        return fm.stringWidth("\u25B6") + Math.max(2, c.px(4)) * 2 + c.px(6);
    }

    private void drawBadge(RenderContext c, ChatEntry e, Font fBadge, int x, int base) {
        Draw.badge(c, "\u25B6", x, base, fBadge, YT_RED);
    }

    private Color nameColor(RenderContext c, ChatEntry e) {
        return switch (e.source()) {
            case YOUTUBE_MOD -> MOD_BLUE;
            case YOUTUBE_SUPER -> c.theme.light() ? new Color(176, 112, 0) : SUPER_GOLD;
            default -> Draw.nameColor(c, e.name());
        };
    }
}
