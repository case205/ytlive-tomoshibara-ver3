package dev.ytstream.stream;

import dev.ytstream.util.TextUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** BGMフォルダの走査。フォルダ外へのシンボリックリンクは除外する。 */
public final class BgmLibrary {
    private static final Set<String> EXT = Set.of("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus");
    private static final int MAX_FILES = 500;

    private BgmLibrary() {}

    public static List<Path> scan(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        Path realDir;
        try {
            realDir = dir.toRealPath();
        } catch (IOException e) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = n.lastIndexOf('.');
                if (dot < 0 || !EXT.contains(n.substring(dot + 1))) continue;
                try {
                    Path real = p.toRealPath();
                    if (!real.startsWith(realDir) || !Files.isRegularFile(real) || Files.size(real) == 0) continue;
                    out.add(real);
                } catch (IOException ignored) {
                    continue;
                }
                if (out.size() >= MAX_FILES) break;
            }
        } catch (IOException ignored) {
            // 読めなければ空扱い
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** 画面に出す曲名(拡張子なし・サニタイズ済み)。 */
    public static String title(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return TextUtil.sanitize(dot > 0 ? n.substring(0, dot) : n, 60);
    }
}
