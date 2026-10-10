package com.gmsoft.salat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class AdhanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent s = new Intent(context, AdhanService.class);
            s.putExtra("title", intent.getStringExtra("title"));
            s.putExtra("src", intent.getStringExtra("src"));
            s.putExtra("id", intent.getStringExtra("id"));
            context.startForegroundService(s);
        } catch (Exception e) {
            Log.e("AdhanReceiver", "start service failed", e);
        }
    }
}
