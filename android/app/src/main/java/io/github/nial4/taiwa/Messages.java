package io.github.nial4.taiwa;

import android.content.Context;

/** Relay and transport error codes in the TV's own language; unknown codes keep the relay's text. */
final class Messages {
    private Messages() {}

    static String error(Context c, String code, String fallback, String host) {
        if (code == null) return fallback;
        switch (code) {
            case "network": return c.getString(R.string.err_network, host);
            case "stream_interrupted": return c.getString(R.string.err_stream_interrupted);
            case "http_401": return c.getString(R.string.err_bad_token);
            case "not_signed_in":
            case "subscription_sharing_invalid_user": return c.getString(R.string.err_not_signed_in);
            case "plan_usage_disabled": return c.getString(R.string.err_plan_usage);
            case "token_error": return c.getString(R.string.err_token);
            case "no_model": return c.getString(R.string.err_no_model);
            case "stt_failed": return c.getString(R.string.err_stt);
            case "no_speech": return c.getString(R.string.err_no_speech);
            case "no_answer": return c.getString(R.string.err_no_answer);
            case "subscription_sharing_usage_limit_exceeded": return c.getString(R.string.err_usage_limit);
            default:
                if (code.startsWith("http_")) return c.getString(R.string.err_http, code.substring(5));
                return fallback == null || fallback.isEmpty() ? code : fallback;
        }
    }
}
