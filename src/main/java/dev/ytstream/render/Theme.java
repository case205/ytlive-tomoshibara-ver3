package dev.ytstream.render;

import java.awt.Color;

/**
 * 配信画面の配色。新しいテーマは byName に追加するだけ。
 * accent / panelColor / textColor は config(theme.accent / panel-color / text-color)で上書きできる。
 */
public record Theme(Color panel, Color border, Color text, Color dim, Color accent, Color live, Color join, Color leave,
                    Color shadow, Color track, boolean light) {

    public static Theme byName(String name, String accentHex, int panelOpacityPercent) {
        return byName(name, accentHex, panelOpacityPercent, "", "");
    }

    public static Theme byName(String name, String accentHex, int panelOpacityPercent, String panelHex, String textHex) {
        int alpha = Math.max(0, Math.min(255, panelOpacityPercent * 255 / 100));
        String n = name == null ? "default" : name.toLowerCase();
        Color panelRgb;
        Color accent;
        boolean light = false;
        switch (n) {
            case "nether" -> {
                panelRgb = new Color(38, 10, 12);
                accent = new Color(255, 120, 70);
            }
            case "end" -> {
                panelRgb = new Color(24, 14, 40);
                accent = new Color(190, 130, 255);
            }
            case "ocean" -> {
                panelRgb = new Color(8, 24, 40);
                accent = new Color(64, 200, 240);
            }
            case "sakura" -> {
                panelRgb = new Color(44, 16, 30);
                accent = new Color(255, 150, 190);
            }
            case "midnight" -> {
                panelRgb = new Color(6, 8, 14);
                accent = new Color(255, 196, 70);
            }
            case "light" -> {
                panelRgb = new Color(250, 251, 253);
                accent = new Color(30, 140, 90);
                light = true;
            }
            default -> {
                panelRgb = new Color(14, 18, 26);
                accent = new Color(96, 214, 104);
            }
        }
        if (accentHex != null && accentHex.matches("#[0-9a-fA-F]{6}")) accent = Color.decode(accentHex);
        if (panelHex != null && panelHex.matches("#[0-9a-fA-F]{6}")) {
            panelRgb = Color.decode(panelHex);
            light = luminance(panelRgb) > 0.6;
        }
        Color text = light ? new Color(22, 26, 34) : new Color(245, 247, 250);
        Color dim = light ? new Color(90, 98, 112) : new Color(190, 198, 210);
        if (textHex != null && textHex.matches("#[0-9a-fA-F]{6}")) {
            text = Color.decode(textHex);
            dim = mix(text, panelRgb, 0.30);
        }
        return new Theme(
                new Color(panelRgb.getRed(), panelRgb.getGreen(), panelRgb.getBlue(), alpha),
                light ? new Color(0, 0, 0, 30) : new Color(255, 255, 255, 46),
                text,
                dim,
                accent,
                new Color(226, 48, 48),
                new Color(96, 214, 104),
                new Color(240, 98, 98),
                light ? new Color(255, 255, 255, 110) : new Color(0, 0, 0, 150),
                light ? new Color(0, 0, 0, 30) : new Color(255, 255, 255, 40),
                light);
    }

    private static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int) Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }
}
