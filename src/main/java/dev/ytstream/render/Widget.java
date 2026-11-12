package dev.ytstream.render;

import dev.ytstream.model.ServerModel;

import java.awt.Rectangle;

/**
 * 配信画面の部品。新機能はこのインターフェースを実装して FrameRenderer に登録する。
 *
 * <p>描画は2層: {@link #renderStatic} は起動時に1回だけ背景へ焼き込む部分(パネル・見出し)、
 * {@link #render} は内容が変わったときだけ、自分の領域だけを描き直す部分。
 */
interface Widget {
    /** config の layout キー(header / chat …)。 */
    String region();

    /** この部品が描画しうる範囲(部分再描画で復元・クリップする範囲)。 */
    default Rectangle area(RenderContext c) {
        Rectangle r = c.rect(region());
        int m = c.px(4);
        return new Rectangle(r.x - m, r.y - m, r.width + 2 * m, r.height + 2 * m);
    }

    /** 変化しない部分(背景へ焼き込まれる)。 */
    default void renderStatic(RenderContext c) {}

    void render(RenderContext c);

    /** アニメーション中(毎フレーム再描画が必要)か。静止時はフレーム再利用で負荷を抑える。 */
    boolean isAnimating(long now, ServerModel model);

    /** 時刻だけで見た目が変わる場合の更新頻度(Hz)。0 = 時刻に依存しない。 */
    default int tickHz() {
        return 0;
    }
}
