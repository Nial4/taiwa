package io.github.nial4.taiwa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Stores the relay address, token and voice pushed over adb (see AndroidManifest for the command). */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(RelayClient.PREFS, Context.MODE_PRIVATE).edit();
        StringBuilder changed = new StringBuilder();
        for (String key : new String[]{"url", "token", "voice", "ui_locale"}) {
            String value = intent.getStringExtra(key);
            if (value == null) continue;
            if (value.isEmpty()) e.remove(key); else e.putString(key, value);
            changed.append(' ').append(key);
        }
        e.apply();
        Log.i("Taiwa", "config updated:" + changed);
        setResultData(changed.length() == 0 ? "nothing to update (extras: url, token, voice, ui_locale)" : "ok" + changed);
    }
}
