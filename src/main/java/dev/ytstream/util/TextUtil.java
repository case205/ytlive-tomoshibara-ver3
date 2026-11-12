package dev.ytstream.util;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

public final class TextUtil {
    private TextUtil() {}

    /** 制御文字・色コード・書式文字(RTL上書き等)を除去し、空白を畳み、長さを制限する。 */
    public static String sanitize(String in, int maxCodePoints) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(in.length(), maxCodePoints * 2));
        boolean lastSpace = true;
        int count = 0;
        int i = 0;
        int len = in.length();
        while (i < len && count < maxCodePoints) {
            int cp = in.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\u00A7') { // 色コード §x
                if (i < len) i++;
                continue;
            }
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace) {
                    sb.append(' ');
                    count++;
                }
                lastSpace = true;
                continue;
            }
            if (Character.getType(cp) == Character.FORMAT) continue;
            sb.appendCodePoint(cp);
            count++;
            lastSpace = false;
        }
        boolean truncated = i < len;
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        sb.setLength(end);
        if (truncated) sb.append('\u2026');
        return sb.toString();
    }

    public static String ellipsize(FontMetrics fm, String s, int maxW) {
        if (maxW <= 0) return "";
        if (fm.stringWidth(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int next = i + Character.charCount(cp);
            String candidate = sb + s.substring(i, next) + "\u2026";
            if (fm.stringWidth(candidate) > maxW) break;
            sb.appendCodePoint(cp);
            i = next;
        }
        return sb + "\u2026";
    }

    /** 幅に合わせて最大 maxLines 行に折り返す。収まらない場合は最終行を省略記号で切る。 */
    public static List<String> wrap(FontMetrics fm, String text, int maxW, int maxLines) {
        List<String> lines = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n && lines.size() < maxLines) {
            while (start < n && text.charAt(start) == ' ' && !lines.isEmpty()) start++;
            if (start >= n) break;
            if (lines.size() == maxLines - 1) {
                lines.add(ellipsize(fm, text.substring(start), maxW));
                break;
            }
            int i = start;
            int lastSpace = -1;
            while (i < n) {
                int cp = text.codePointAt(i);
                int next = i + Character.charCount(cp);
                if (fm.stringWidth(text.substring(start, next)) > maxW) break;
                if (cp == ' ') lastSpace = i;
                i = next;
            }
            if (i == start) i = start + Character.charCount(text.codePointAt(start));
            if (i >= n) {
                lines.add(text.substring(start));
                break;
            }
            if (lastSpace > start) {
                lines.add(text.substring(start, lastSpace));
                start = lastSpace + 1;
            } else {
                lines.add(text.substring(start, i));
                start = i;
            }
        }
        return lines;
    }
}
