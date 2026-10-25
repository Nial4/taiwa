package io.github.nial4.taiwa;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Finds relays advertised on the LAN as {@code _taiwa._tcp} (see relay/taiwa_relay/discovery.py). */
final class Discovery {
    interface Callback {
        /** @param found "name\nhttp://ip:port" entries; empty when nothing answered in time */
        void onFinished(List<String[]> found);
    }

    private static final String TAG = "TaiwaNsd";
    private static final String TYPE = "_taiwa._tcp";

    private final NsdManager nsd;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> found = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private boolean finished;
    private NsdManager.DiscoveryListener listener;

    Discovery(Context context) {
        nsd = context.getSystemService(NsdManager.class);
    }

    void run(long timeoutMs, Callback cb) {
        listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "discovery failed " + code);
                ui.post(() -> finish(cb));
            }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                ui.post(() -> {
                    toResolve.add(info);
                    resolveNext(cb);
                });
            }
        };
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        ui.postDelayed(() -> finish(cb), timeoutMs);
    }

    /** NsdManager on Android 11 resolves one service at a time. */
    private void resolveNext(Callback cb) {
        if (resolving || finished || toResolve.isEmpty()) return;
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int code) {
                ui.post(() -> { resolving = false; resolveNext(cb); });
            }
            @Override public void onServiceResolved(NsdServiceInfo info) {
                ui.post(() -> {
                    resolving = false;
                    if (info.getHost() != null && info.getHost().getAddress().length == 4) {
                        String url = "http://" + info.getHost().getHostAddress() + ":" + info.getPort();
                        boolean dup = false;
                        for (String[] f : found) dup |= f[1].equals(url);
                        if (!dup) found.add(new String[]{info.getServiceName(), url});
                        Log.i(TAG, "found " + info.getServiceName() + " " + url);
                    }
                    resolveNext(cb);
                });
            }
        });
    }

    private void finish(Callback cb) {
        if (finished) return;
        finished = true;
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (IllegalArgumentException ignored) {
            // discovery never started
        }
        cb.onFinished(found);
    }
}
