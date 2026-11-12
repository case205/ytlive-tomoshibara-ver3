package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.model.ServerModel.PlayerEntry;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** プレイヤー一覧(顔アイコン・名前・統合版バッジ・応答速度バー)。 */
final class PlayersWidget implements Widget {
    private static final Color BEDROCK = new Color(70, 140, 230);
    private final boolean showPing;
    private final boolean showPlatform;
    private final boolean sortByName;

    PlayersWidget(Settings s) {
        this.showPing = s.playersView().showPing();
        this.showPlatform = s.playersView().showPlatform();
        this.sortByName = s.playersView().sort().equals("name");
    }

    @Override
    public String region() {
        return "players";
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        return false;
    }

    @Override
    public void renderStatic(RenderContext c) {
        Draw.staticPanel(c, c.rect("players"), false);
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("players");
        List<PlayerEntry> list = c.model.players();
        if (sortByName && list.size() > 1) {
            list = new ArrayList<>(list);
            list.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        }

        int pad = c.px(14);
        Font fTitle = c.font(Font.BOLD, 21);
        FontMetrics fmT = g.getFontMetrics(fTitle);
        g.setFont(fTitle);
        Draw.text(c, TextUtil.ellipsize(fmT, c.s.label("players") + " (" + list.size() + ")", r.width - 2 * pad),
                r.x + pad, r.y + pad + fmT.getAscent(), t.text());

        int rowH = c.px(36);
        int head = c.px(28);
        int top = r.y + pad + fmT.getHeight() + c.px(8);
        int rows = Math.max(0, (r.y + r.height - pad - top) / rowH);
        int show = list.size() <= rows ? list.size() : Math.max(0, rows - 1);

        Font fName = c.font(Font.PLAIN, 20);
        FontMetrics fmN = g.getFontMetrics(fName);
        Font fInit = c.font(Font.BOLD, 16);
        Font fBadge = c.font(Font.BOLD, 12);
        int barH = c.px(16);
        int barW = showPing ? Draw.pingBarsWidth(barH) : 0;
        int nameX = r.x + pad + head + c.px(10);
        int right = r.x + r.width - pad;
        for (int i = 0; i < show; i++) {
            PlayerEntry p = list.get(i);
            int y = top + i * rowH;
            int base = y + (rowH + fmN.getAscent() - fmN.getDescent()) / 2;
            if (i % 2 == 1) { // 1行おきに薄い帯(視認性)
                g.setColor(t.light() ? new Color(0, 0, 0, 10) : new Color(255, 255, 255, 10));
                g.fillRoundRect(r.x + pad / 2, y + c.px(2), r.width - pad, rowH - c.px(4), c.px(8), c.px(8));
            }
            Draw.head(g, p, r.x + pad, y + (rowH - head) / 2, head, fInit);
            int nameMax = right - nameX - (showPing ? barW + c.px(8) : 0);
            int bx = nameX;
            if (showPlatform && p.bedrock) {
                int bw = Draw.badge(c, "BE", nameX, base, fBadge, BEDROCK);
                bx += bw + c.px(6);
                nameMax -= bw + c.px(6);
            }
            g.setFont(fName);
            String display = p.bedrock && showPlatform && !c.s.bedrockPrefix().isEmpty() && p.name.startsWith(c.s.bedrockPrefix())
                    ? p.name.substring(c.s.bedrockPrefix().length()) : p.name;
            Draw.text(c, TextUtil.ellipsize(fmN, display, nameMax), bx, base, t.text());
            if (showPing) Draw.pingBars(c, ServerModel.pingBars(p.ping), right - barW, y + (rowH + barH) / 2, barH);
        }
        if (list.size() > show) {
            int y = top + show * rowH;
            g.setFont(fName);
            String more = c.s.label("more").replace("{n}", String.valueOf(list.size() - show));
            Draw.text(c, TextUtil.ellipsize(fmN, more, r.width - 2 * pad), r.x + pad,
                    y + (rowH + fmN.getAscent() - fmN.getDescent()) / 2, t.dim());
        }
    }
}
