package dev.ytstream.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** FFmpegの探索と検証。FFmpegは同梱せず、利用者の環境のものを使う(ライセンス上の配慮)。 */
public final class FfmpegLocator {
    private FfmpegLocator() {}

    public record Info(Path path, String version, boolean hasCodec) {}

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 設定パス → plugins/YtStream/ffmpeg/ → PATH の順に探す。見つからなければ null。 */
    public static Path find(String configured, Path dataDir) {
        String exeName = windows() ? "ffmpeg.exe" : "ffmpeg";
        if (configured != null && !configured.isBlank() && !configured.equals("ffmpeg")) {
            Path p = Path.of(configured);
            if (!p.isAbsolute()) p = dataDir.resolve(configured).normalize();
            return Files.isRegularFile(p) ? p : null;
        }
        Path local = dataDir.resolve("ffmpeg").resolve(exeName);
        if (Files.isRegularFile(local)) return local;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                try {
                    Path p = Path.of(dir).resolve(exeName);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) return p;
                } catch (RuntimeException ignored) {
                    // 不正なPATH要素は無視
                }
            }
        }
        return null;
    }

    public static Info verify(Path exe, String codec) throws IOException, InterruptedException {
        String version = run(exe, "-version");
        String first = version.lines().findFirst().orElse("");
        if (!first.toLowerCase().contains("ffmpeg")) {
            throw new IOException("ffmpegではないか、実行できません: " + exe.getFileName());
        }
        String encoders = run(exe, "-hide_banner", "-encoders");
        boolean has = encoders.lines().anyMatch(l -> l.matches("^\\s*V\\S*\\s+" + java.util.regex.Pattern.quote(codec) + "\\s.*"));
        return new Info(exe, first, has);
    }

    private static String run(Path exe, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = exe.toString();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpegの応答がありません(タイムアウト)");
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public static String installHint() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String how;
        if (os.contains("win")) {
            how = "Windows: PowerShellで `winget install Gyan.FFmpeg` を実行し、サーバーを再起動してください。";
        } else if (os.contains("mac")) {
            how = "macOS: `brew install ffmpeg` を実行してください。";
        } else {
            how = "Linux: `sudo apt install ffmpeg`(Debian/Ubuntu) / `sudo dnf install ffmpeg`(Fedora) を実行してください。";
        }
        return how + " または ffmpeg 実行ファイルを plugins/YtStream/ffmpeg/ に置くか、config.yml の ffmpeg.path に指定してください。";
    }
}
