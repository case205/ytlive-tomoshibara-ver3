package dev.ytstream.util;

/** 文字列化してもログに漏れないシークレット保持クラス。 */
public final class Secret {
    public static final Secret EMPTY = new Secret("");
    private final String value;

    public Secret(String value) {
        this.value = value == null ? "" : value;
    }

    public boolean isSet() {
        return !value.isEmpty();
    }

    public String reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "<hidden>";
    }
}
