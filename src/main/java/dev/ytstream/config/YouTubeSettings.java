package dev.ytstream.config;

/**
 * YouTube Data API 連携の設定(config.yml の youtube.api.*)。
 * 有効にすると、配信枠の作成→ストリームへの紐付け→「ライブ開始」への切替→終了までをプラグインが行うため、
 * YouTube Studio(配信ページ)を開いていなくても配信が確実に公開される。
 */
public record YouTubeSettings(
        boolean enabled,
        String clientId,
        String streamSource,        // own = プラグイン専用のストリームを自動作成/再利用 / key = 設定済みストリームキーのストリームを使う
        String streamTitle,
        String broadcastMode,       // reuse = 予定/配信中の枠を再利用し無ければ作成 / create = 毎回新規作成
        String title,
        String description,
        String privacy,             // public | unlisted | private
        boolean madeForKids,
        String latency,             // normal | low | ultraLow
        boolean dvr,
        boolean embed,
        boolean autoStop,
        boolean endOnStop,
        int goLiveTimeoutSec,
        boolean fallbackToKey,
        boolean viewers,
        int viewersIntervalSec,
        boolean health,
        boolean chat,
        int chatIntervalSec,
        boolean chatToGame,
        boolean chatOnStream,
        String chatFormat,
        int quotaBudget,
        int quotaReserve,
        String timezone,
        int httpTimeoutSec) {

    public static YouTubeSettings defaults() {
        return new YouTubeSettings(false, "", "own", "YtStream", "reuse",
                "{server} ライブ配信", "Minecraftサーバー「{server}」のライブ配信です。", "public", false,
                "normal", true, true, false, true, 90, true,
                true, 60, true,
                false, 30, true, true, "[YouTube] {name}: {message}",
                10000, 600, "", 15);
    }
}
