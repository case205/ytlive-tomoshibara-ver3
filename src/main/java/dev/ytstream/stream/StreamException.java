package dev.ytstream.stream;

/** 利用者にそのまま表示できる日本語メッセージを持つ例外。fatal=true は再試行しても直らない問題(設定・キー・FFmpeg未導入)。 */
public final class StreamException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean fatal;

    public StreamException(String message) {
        this(message, false);
    }

    public StreamException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public boolean fatal() {
        return fatal;
    }
}
