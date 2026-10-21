package dev.pico.facialprobe;

import android.util.Log;
import android.os.SystemClock;
import java.util.ArrayDeque;

final class BridgeState {
    static final String LOG_TAG = "PicoFacialBridge";
    static volatile int status = R.string.status_stopped, detail = R.string.detail_initial, clientMessage = R.string.client_none;
    static volatile String error = "", client = "", address = "-";
    static volatile boolean eye, face, udp;
    // Deliberately not persisted: a new application process starts with values logging off.
    static volatile boolean eyeDebug;
    static volatile TrackingMode.Info mode = TrackingMode.normal();
    static volatile long packets, eyeFrames, faceFrames;
    static volatile long ringOverruns;
    static final TransmissionRates rates = new TransmissionRates();
    private static final ArrayDeque<String> logs = new ArrayDeque<>();
    static synchronized void log(String message) {
        if (message == null) return;
        message = "tMs=" + SystemClock.elapsedRealtime() + " " + message;
        if (message.length() > 2048) message = message.substring(0,2048) + " [truncated]";
        Log.i(LOG_TAG, message);
        if (logs.size() >= 160) logs.removeFirst();
        logs.addLast(message);
    }
    static synchronized String logs() { return String.join("\n", logs); }
}
