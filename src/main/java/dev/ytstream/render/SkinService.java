package dev.ytstream.render;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * プレイヤースキンの顔画像を非同期取得する。
 * 通信先は https://textures.minecraft.net/texture/<hash> のみに固定(SSRF対策)、
 * リダイレクト無効・サイズ上限・タイムアウト付き。失敗しても配信には影響しない。
 */
public final class SkinService {
    private static final Pattern PATH = Pattern.compile("^/texture/[0-9a-fA-F]{16,128}$");
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DIM = 512;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ytstream-skin");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, BufferedImage> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
                    return size() > 256;
                }
            });

    public void loadHead(String url, Consumer<BufferedImage> callback) {
        if (url == null) return;
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return;
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (host == null || !host.equalsIgnoreCase("textures.minecraft.net") || path == null || !PATH.matcher(path).matches()) {
            return;
        }
        String key = path;
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        URI target = URI.create("https://textures.minecraft.net" + path);
        try {
            pool.execute(() -> {
                try {
                    BufferedImage head = fetch(target);
                    if (head != null) {
                        cache.put(key, head);
                        callback.accept(head);
                    }
                } catch (Exception ignored) {
                    // スキン取得失敗はプレースホルダ表示で継続
                }
            });
        } catch (RuntimeException ignored) {
            // シャットダウン後など
        }
    }

    private BufferedImage fetch(URI target) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "YtStream")
                .GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) return null;
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) return null;
            return decodeHead(data);
        }
    }

    static BufferedImage decodeHead(byte[] data) throws Exception {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            ImageReader reader = it.next();
            try {
                reader.setInput(iis);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 64 || h < 32 || w > MAX_DIM || h > MAX_DIM) return null;
                BufferedImage skin = reader.read(0);
                int sc = Math.max(1, w / 64);
                BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(skin, 0, 0, 64, 64, 8 * sc, 8 * sc, 16 * sc, 16 * sc, null);
                g.drawImage(skin, 0, 0, 64, 64, 40 * sc, 8 * sc, 48 * sc, 16 * sc, null);
                g.dispose();
                return out;
            } finally {
                reader.dispose();
            }
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
