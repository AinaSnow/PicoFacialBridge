package dev.pico.facialprobe;

import java.util.List;

/**
 * Emits one datagram per source update, in timestamp order. Eye and facial updates are separate
 * datagrams, so a fast eye update is never delayed by, and never re-sends, a slow facial sample.
 */
final class FramePump {
    interface Sink { void send(byte[] packet) throws Exception; }
    private byte[] eye, face;
    private long leftObservation, rightObservation, mouthObservation;
    private int expressionVersion;
    private boolean mouthValid;
    /** Enhance mode with the dual plugin asks to keep the real per-eye validity bits on the wire. */
    boolean keepPerEyeGaze;
    /** Acknowledged subscription epoch carried in every datagram header. */
    long epoch;
    byte[] eye() { return eye; }
    byte[] face() { return face; }
    boolean faceValid(long now) { return mouthValid && TrackingData.fresh(now, mouthObservation); }
    boolean morphologyFresh(long now, int mask) {
        if (face == null || mask == 0) return false;
        return ((mask & 1) == 0 || TrackingData.fresh(now, leftObservation) && TrackingData.fresh(now, rightObservation)) &&
                ((mask & 2) == 0 || TrackingData.fresh(now, mouthObservation));
    }
    private void observeFace() {
        int version = TrackingData.independentVersion(face);
        if (version != expressionVersion) {
            leftObservation = rightObservation = mouthObservation = 0;
            mouthValid = false;
            expressionVersion = version;
        }
        java.nio.ByteBuffer src = TrackingData.bytes(face);
        int updated = version == 2 ? src.getInt(236) : 7;
        long left = src.getLong(0), right = version == 2 ? src.getLong(248) : left,
                mouth = version == 2 ? src.getLong(240) : left;
        // Invalid observations still advance identity; quality alone is not a source stall.
        if ((updated & 1) != 0 && left > leftObservation) leftObservation = left;
        if ((updated & 2) != 0 && right > rightObservation) rightObservation = right;
        if ((updated & 4) != 0 && mouth > mouthObservation) {
            mouthObservation = mouth;
            mouthValid = TrackingData.faceValid(face);
        }
    }
    void accept(List<byte[]> eyes, List<byte[]> faces, long now, Sink sink) throws Exception {
        accept(eyes, faces, now, true, true, sink);
    }
    void accept(List<byte[]> eyes, List<byte[]> faces, long now, boolean sendEye, boolean sendFace, Sink sink) throws Exception {
        int mask = (sendEye ? 1 : 0) | (sendFace ? 2 : 0);
        int e = 0, f = 0;
        while (e < eyes.size() || f < faces.size()) {
            long eyeTime = e < eyes.size() ? TrackingData.eyeTimestamp(eyes.get(e)) : Long.MAX_VALUE;
            long faceTime = f < faces.size() ? TrackingData.faceTimestamp(faces.get(f)) : Long.MAX_VALUE;
            boolean eyeChanged = eyeTime <= faceTime, faceChanged = faceTime <= eyeTime;
            if (eyeChanged) eye = eyes.get(e++);
            if (faceChanged) { face = faces.get(f++); observeFace(); }
            if (eyeChanged && sendEye && TrackingData.eyeFresh(eye, now))
                sink.send(TrackingData.eyePacket(eye, keepPerEyeGaze, mask, epoch));
            // Eye-only still needs expression morphology, sent as A or independent X.
            if (faceChanged && mask != 0) {
                byte[] packet = TrackingData.facePacket(face, mask, epoch, now);
                if (packet != null) sink.send(packet);
            }
        }
    }
}
