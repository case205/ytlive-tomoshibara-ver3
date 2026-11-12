package dev.ytstream.youtube;

/** Google/YouTube APIのエラー。status=0 は通信エラー(未到達・タイムアウト)。 */
public final class ApiException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String reason;

    public ApiException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason == null ? "" : reason;
    }

    public int status() {
        return status;
    }

    /** APIのエラー理由(例: quotaExceeded, invalidTransition, redundantTransition)。 */
    public String reason() {
        return reason;
    }

    /** 時間をおけば直る可能性が高い(通信断・5xx・レート制限)。 */
    public boolean isTransient() {
        return status == 0 || status >= 500 || status == 429 || reason.equals("rateLimitExceeded")
                || reason.equals("backendError");
    }

    public boolean isQuota() {
        return reason.equals("quotaExceeded") || reason.equals("dailyLimitExceeded");
    }

    /** 認証が無効(再認証が必要)。 */
    public boolean isAuth() {
        return status == 401 || reason.equals("invalid_grant") || reason.equals("unauthorized_client")
                || reason.equals("invalid_client") || reason.equals("authError");
    }
}
