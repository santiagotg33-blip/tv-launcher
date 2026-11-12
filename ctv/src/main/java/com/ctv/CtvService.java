package com.ctv;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class CtvService extends Service {
    static volatile CtvServer server;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null || !server.isRunning()) {
            final CtvServer s = new CtvServer(getApplicationContext());
            server = s;
            new Thread(new Runnable() {
                public void run() {
                    try { s.start(); }
                    catch (Throwable t) {
                        s.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    }
                }
            }).start();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        server = null;
        super.onDestroy();
    }
}
