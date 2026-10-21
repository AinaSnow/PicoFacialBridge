package dev.pico.facialprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.DatagramSocket;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BridgeService extends Service {
    static final String START = "dev.pico.bridge.START", STOP = "dev.pico.bridge.STOP", RESTART = "dev.pico.bridge.RESTART";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private boolean wanted, working, destroyed;
    private SharedPreferences prefs;
    private volatile int transmitMask = 3;
    private final Object transmissionLock = new Object();
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (preferences, key) -> {
        if (BridgePreferences.EYE.equals(key) || BridgePreferences.FACE.equals(key)) {
            synchronized (transmissionLock) {
                transmitMask = BridgePreferences.selection(preferences);
                BridgeState.rates.reset();
            }
            BridgeState.log("TRANSMISSION eye=" + ((transmitMask & 1) != 0) + " face=" + ((transmitMask & 2) != 0));
        } else if (BridgePreferences.LANGUAGE.equals(key)) {
            updateChannel();
            if (wanted || working) getSystemService(NotificationManager.class).notify(1, notification());
        }
    };
    private PowerManager.WakeLock wake;

    @Override public void onCreate() {
        super.onCreate();
        prefs = BridgePreferences.get(this);
        transmitMask = BridgePreferences.selection(prefs);
        prefs.registerOnSharedPreferenceChangeListener(settingsChanged);
        updateChannel();
    }
    private void updateChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("tracking", BridgePreferences.localized(this).getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? START : intent.getAction();
        BridgeState.log("SERVICE_REQUEST action=" + action + " working=" + working + " sticky=" + (intent == null));
        startForeground(1, notification());
        if (STOP.equals(action)) {
            wanted = false; cancel.set(true);
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("wanted", false).apply();
            if (!working) stopNow();
        } else {
            wanted = intent != null || getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("wanted", false);
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("wanted", wanted).apply();
            if (RESTART.equals(action)) cancel.set(true);
            if (!working && wanted) launch();
            else if (!wanted && !working) stopNow();
        }
        return START_STICKY;
    }
    private Notification notification() {
        Context localized = BridgePreferences.localized(this);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, BridgeActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "tracking").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("PicoFacialBridge OSC Direct").setContentText(localized.getString(R.string.notification_active))
                .setOngoing(true).setContentIntent(open).addAction(android.R.drawable.ic_media_pause, localized.getString(R.string.stop), stop).build();
    }
    private void launch() {
        cancel.set(false); working = true;
        BridgeState.status = R.string.status_starting; BridgeState.detail = R.string.detail_connecting; BridgeState.error = "";
        BridgeState.rates.reset();
        new Thread(() -> {
            boolean failed = false;
            try { runBridge(); }
            catch (Exception | LinkageError e) {
                if (!cancel.get()) { failed = true; BridgeState.status = R.string.status_error; BridgeState.detail = R.string.detail_error; BridgeState.error = e.toString(); BridgeState.log("FATAL workerStopped exception=" + e); }
            } finally {
                boolean finalFailed = failed;
                main.post(() -> {
                    working = false;
                    if (destroyed) return;
                    if (finalFailed) {
                        wanted = false;
                        getSharedPreferences("bridge", MODE_PRIVATE).edit().putBoolean("wanted", false).apply();
                        stopForeground(true); stopSelf();
                    } else if (wanted) launch(); else stopNow();
                });
            }
        }, "PicoFacialBridge").start();
    }

    private void runBridge() throws Exception {
        for (String p : new String[]{"com.picovr.permission.EYE_TRACKING", "com.picovr.permission.FACE_TRACKING"}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Grant permission in app: " + p);
        }
        String host = prefs.getString(BridgePreferences.HOST, "").trim();
        int port = prefs.getInt(BridgePreferences.PORT, 9000), hz = prefs.getInt(BridgePreferences.HZ, 60);
        OscDestination.validate(host, port, hz);
        InetSocketAddress destination = new InetSocketAddress(InetAddress.getByAddress(OscDestination.address(host)), port);
        TrackingSession session = new TrackingSession(BridgeState::log);
        BridgeDiagnostics diagnostics = new BridgeDiagnostics(SystemClock.elapsedRealtimeNanos());
        DirectFaceMapper mapper = new DirectFaceMapper(diagnostics);
        OscFacePacket osc = new OscFacePacket();
        EyeLookDiagnostics eyeDiagnostics = new EyeLookDiagnostics();
        DatagramPacket packet = new DatagramPacket(osc.bytes, osc.bytes.length, destination);
        DatagramSocket socket = new DatagramSocket();
        boolean active = false;
        DeadlinePacer pacer = new DeadlinePacer(hz, SystemClock.elapsedRealtimeNanos());
        try {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoFacialBridge:tracking");
            wake.acquire();
            BridgeState.address = host + ":" + port;
            BridgeState.client = ""; BridgeState.clientMessage = R.string.client_none;
            BridgeState.udp = true;
            BridgeState.packets = BridgeState.eyeFrames = BridgeState.faceFrames = BridgeState.ringOverruns = 0;
            BridgeState.log("BEGIN version=" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                    + " transport=OSC target=" + BridgeState.address + " configuredMaxHz=" + hz
                    + " mask=" + transmitMask + " bytesPerMessage=" + OscFacePacket.SIZE
                    + " pupil=unsupported gazeSource=morphology noOpenXR=true receiver=unconfirmed");
            long nextStart = 0, nextModeCheck = 0, reportedOverruns = 0, nextSend = 0;
            long lastFresh = SystemClock.elapsedRealtime();
            int lastSelection = transmitMask;
            String startError = null, sendError = null;
            while (!cancel.get()) {
                long now = SystemClock.elapsedRealtime();
                int selection = transmitMask;
                if (selection != lastSelection) {
                    BridgeState.log("CONFIG_APPLIED mask=" + selection + " previousMask=" + lastSelection + " cacheReset=true");
                    mapper.reset(); BridgeState.rates.reset(); nextSend = 0;
                    lastSelection = selection; lastFresh = now; nextStart = 0;
                    // Explicitly clear disabled channels (including both-off) on the receiver.
                    osc.neutral();
                    try { socket.send(packet); diagnostics.sent(true); BridgeState.packets++; sendError = null; }
                    catch (java.io.IOException e) { sendError = e.toString(); BridgeState.log(diagnostics.error(BridgeDiagnostics.UDP,e,SystemClock.elapsedRealtimeNanos())); }
                }
                if (now >= nextModeCheck) {
                    if (refreshMode()) { mapper.reset(); BridgeState.rates.reset(); lastFresh = now; }
                    nextModeCheck = now + 1000;
                }
                if (selection == 0 && active) {
                    BridgeState.log("CAPTURE_PAUSED reason=allChannelsDisabled");
                    session.close(); active = false; mapper.reset();
                }
                if (StreamHealth.reconnectDue(active, session.isAlive(), now, lastFresh)) {
                    diagnostics.reconnect();
                    BridgeState.log("RECONNECT binderAlive=" + session.isAlive() + " requiredSourceInactiveMs=" + (now-lastFresh));
                    session.close(); active = false; mapper.reset(); nextStart = now + 1000;
                }
                if (selection != 0 && !active && now >= nextStart) {
                    try {
                        session.open(); active = true;
                        BridgeState.log("SESSION_OPEN_OK recovered=" + (startError != null));
                        startError = null; lastFresh = SystemClock.elapsedRealtime();
                        mapper.reset(); reportedOverruns = 0; BridgeState.rates.reset();
                    } catch (UnsupportedOperationException e) {
                        BridgeState.log(diagnostics.error(BridgeDiagnostics.OPEN,e,SystemClock.elapsedRealtimeNanos()));
                        throw e;
                    }
                    catch (Exception e) {
                        session.close(); startError = e.toString();
                        BridgeState.log(diagnostics.error(BridgeDiagnostics.OPEN,e,SystemClock.elapsedRealtimeNanos()));
                        nextStart = SystemClock.elapsedRealtime() + 5000;
                    }
                }
                long nano = SystemClock.elapsedRealtimeNanos();
                if (active) {
                    // Keep the existing bounded ring reader and Binder/JNI acquisition path.
                    // Native eye packets provide no normalized pupil or extra avatar channels.
                    List<byte[]> eyes = null, faces = null;
                    try { eyes = session.readEyes(); faces = session.readFaces(); }
                    catch (RuntimeException e) {
                        BridgeState.log(diagnostics.error(BridgeDiagnostics.READ,e,SystemClock.elapsedRealtimeNanos()));
                        startError=e.toString(); session.close(); active=false; mapper.reset();
                        nextStart=SystemClock.elapsedRealtime()+5000;
                    }
                    if (active) {
                        diagnostics.captured(eyes.size(),faces.size(),nano);
                        BridgeState.eyeFrames += eyes.size(); BridgeState.faceFrames += faces.size();
                        for (byte[] face : faces) mapper.accept(face, nano);
                        if (session.overruns() > reportedOverruns) {
                            BridgeState.ringOverruns += session.overruns() - reportedOverruns;
                            reportedOverruns = session.overruns();
                        }
                    }
                }
                BridgeState.eye = active && mapper.eyeFresh(nano);
                BridgeState.face = active && mapper.mouthFresh(nano);
                if (active && mapper.sourcesFresh(nano, selection)) lastFresh = nano / 1_000_000L;
                if (selection != 0 && nano >= nextSend) {
                    osc.encode(mapper.snapshot(nano, selection));
                    BridgeState.log(eyeDiagnostics.sample(BridgeState.eyeDebug, nano, mapper, osc.bytes, selection));
                    synchronized (transmissionLock) {
                        if (selection == transmitMask && !cancel.get()) {
                            try {
                                socket.send(packet); diagnostics.sent(false); BridgeState.packets++; sendError = null;
                                BridgeState.rates.sent(nano, (selection & 1) != 0 ? mapper.eyeTime() : 0,
                                        (selection & 2) != 0 ? mapper.mouthTime() : 0);
                            } catch (java.io.IOException e) {
                                String error = e.toString();
                                BridgeState.log(diagnostics.error(BridgeDiagnostics.UDP,e,SystemClock.elapsedRealtimeNanos()));
                                sendError = error;
                            }
                        }
                    }
                    // No catch-up bursts or queues after a stalled session/network call.
                    nextSend = sendError == null ? 0 : SystemClock.elapsedRealtimeNanos() + 1_000_000_000L;
                }
                boolean live = selection != 0 && ((selection & 1) == 0 || BridgeState.eye) && ((selection & 2) == 0 || BridgeState.face);
                BridgeState.error = sendError != null ? sendError : startError != null ? startError : "";
                BridgeState.status = selection == 0 ? R.string.status_paused : !BridgeState.error.isEmpty() ? R.string.status_error
                        : live ? R.string.status_running : R.string.status_starting;
                BridgeState.detail = selection == 0 ? R.string.detail_paused : sendError != null ? R.string.detail_send_error
                        : startError != null ? R.string.detail_retry : live ? R.string.detail_live : R.string.detail_waiting;
                long diagnosticNow=SystemClock.elapsedRealtimeNanos();
                BridgeState.log(diagnostics.health(diagnosticNow,mapper,selection,active,sendError!=null));
                if (diagnostics.due(diagnosticNow)) {
                    BridgeState.log(diagnostics.summary(diagnosticNow,mapper,selection,BridgeState.address,hz,
                            BridgeState.mode.enhance,BridgeState.ringOverruns));
                }
                // One acquisition pass per bounded send interval; no 1 ms socket polling.
                if (selection == 0) {
                    SystemClock.sleep(100); pacer.reset(SystemClock.elapsedRealtimeNanos());
                } else SystemClock.sleep(pacer.finishAndWaitMillis(SystemClock.elapsedRealtimeNanos()));
            }
        } finally {
            // Best-effort neutral on explicit stop/restart, not an acknowledgment guarantee.
            osc.neutral();
            try { socket.send(packet); diagnostics.sent(true); BridgeState.packets++; }
            catch (java.io.IOException e) { BridgeState.log(diagnostics.error(BridgeDiagnostics.UDP,e,SystemClock.elapsedRealtimeNanos())); }
            BridgeState.log("FINAL " + diagnostics.summary(SystemClock.elapsedRealtimeNanos(),mapper,transmitMask,BridgeState.address,hz,
                    BridgeState.mode.enhance,BridgeState.ringOverruns));
            session.close(); socket.close();
            if (wake != null && wake.isHeld()) wake.release();
            BridgeState.eye = BridgeState.face = BridgeState.udp = false;
            BridgeState.rates.reset();
            BridgeState.log("END worker cleanup complete");
        }
    }
    /**
     * Read on session open and periodically, because publication can follow reconnection.
     * Returns true when the detected
     * mode differs from the previous observation, so cached expressions can be reset.
     */
    private boolean refreshMode() {
        TrackingMode.Info detected = EnhanceDetector.detect();
        if (!detected.sameAs(BridgeState.mode)) {
            BridgeState.mode = detected;
            BridgeState.log("MODE " + TrackingMode.advertise(detected) + " gazeSource=morphology pupil=unsupported cacheReset=true");
            return true;
        }
        return false;
    }
    private void stopNow() {
        BridgeState.status = R.string.status_stopped; BridgeState.detail = R.string.detail_stopped; BridgeState.error = "";
        BridgeState.client = ""; BridgeState.clientMessage = R.string.client_none; BridgeState.rates.reset(); stopForeground(true); stopSelf();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        BridgeState.log("SERVICE_DESTROY cancelWorker=true");
        destroyed = true; wanted = false; cancel.set(true); prefs.unregisterOnSharedPreferenceChangeListener(settingsChanged); super.onDestroy();
    }
}
