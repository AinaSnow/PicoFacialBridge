package dev.pico.facialprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Verified PICO OS 5.13.7 source fields, plus the BridgeSplit wire protocol.
 *
 * <p>The upstream daemon/module pair used one fixed 536-byte datagram: a 384-byte facial prefix
 * followed by a 152-byte eye prefix. Because the source streams can run at different rates, that
 * framing re-sent the unchanged half on every update of the other half. BridgeSplit does not keep it: each datagram
 * carries only the fields the receiver consumes for the channels it subscribed to.
 *
 * <p>Every datagram starts with a 19-byte little-endian header: tag, channel mask (eye=1, face=2),
 * morphology validity (eye=1, face=2), int64 epoch, int64 source timestamp.
 *
 * <pre>
 *   'E' 83 bytes  native eye frame, when the eye channel is subscribed
 *   'A' 95 bytes  19 eye morphology shapes, eye-only subscription
 *   'F' 151 bytes 33 non-eye shapes, face-only subscription
 *   'F' 227 bytes all 52 shapes, both channels subscribed
 *   'X' 111/243 bytes eye-only/both shapes plus independent-eye extension v1
 *   'X' 131/263 bytes extension v2 adds L/R/mouth updates and two source times
 * </pre>
 *
 * <p>Dropped on purpose: the vendor's 3D gaze points, position guides, foveated slots, the
 * unused blendshape slots 52..71 and the viseme/emotion fields. An EPI1 marker in the
 * facial prefix enables X packets carrying right inner brow and per-eye quality states.
 */
public final class TrackingData {
    /** Datagram tags; none collides with the ASCII control datagrams or the {@code MARCO\0} ping. */
    static final byte EYE_TAG = 'E';
    static final byte FACE_TAG = 'F';
    static final byte AUX_TAG = 'A';
    static final byte INDEPENDENT_TAG = 'X';
    static final int HEADER_SIZE = 19;
    static final int EYE_PACKET_SIZE = 83;

    private static final int HEADER_MASK = 1, HEADER_VALID = 2, HEADER_EPOCH = 3, HEADER_TIMESTAMP = 11;
    // Eye frame payload. Source offsets are inside the 200-byte eye sample.
    private static final int EYE_STATUS = 19;          // left/right/combined uint32 <- source 0
    private static final int EYE_VECTORS = 31;         // three float3: left/right/combined <- source 48
    private static final int EYE_OPENNESS = 67;        // left/right <- source 84
    private static final int EYE_PUPIL = 75;           // left/right <- source 92
    // Only the consumed state bits: combined gaze, openness, per-eye gaze vector, pupil.
    private static final int EYE_STATUS_BITS = 0x906;
    private static final int FACE_SHAPE_COUNT = 52;
    private static final int INDEPENDENT_MAGIC = 0x31495045;
    private static final int INDEPENDENT_EXTENSION_SIZE = 16;
    // Stable eye morphology order: ascending PICO shape identifiers (19 float32 fields).
    static final int[] EYE_SHAPES = {0, 2, 3, 4, 11, 12, 16, 26, 28, 30, 31, 35, 36, 38, 41, 44, 45, 46, 47};
    // Complement of EYE_SHAPES: face-only never serializes disabled eye morphology.
    static final int[] FACE_ONLY_SHAPES = {1, 5, 6, 7, 8, 9, 10, 13, 14, 15, 17, 18, 19, 20, 21, 22, 23,
            24, 25, 27, 29, 32, 33, 34, 37, 39, 40, 42, 43, 48, 49, 50, 51};

    private static void header(ByteBuffer dst, byte tag, int mask, int valid, long epoch, long timestamp) {
        dst.put(0, tag); dst.put(HEADER_MASK, (byte) mask); dst.put(HEADER_VALID, (byte) valid);
        dst.putLong(HEADER_EPOCH, epoch); dst.putLong(HEADER_TIMESTAMP, timestamp);
    }

    /**
     * Builds the native eye datagram from a 200-byte vendor eye sample. Vendor non-finite
     * sentinels are zeroed with their validity cleared, so an invalidation reaches the receiver
     * rather than freezing a frame.
     */
    static byte[] eyePacket(byte[] eye, boolean keepPerEyeGaze, int mask, long epoch) {
        if (eye == null || eye.length < 200) throw new IllegalArgumentException("Eye prefix too short");
        byte[] packet = new byte[EYE_PACKET_SIZE];
        ByteBuffer src = bytes(eye), dst = bytes(packet);
        header(dst, EYE_TAG, mask, 0, epoch, src.getLong(168));
        for (int i = 0; i < 3; i++) {
            int status = src.getInt(i * 4);
            if (i < 2 && !keepPerEyeGaze) status &= ~STRIP_PER_EYE_GAZE_FLAGS;
            status &= EYE_STATUS_BITS;
            for (int j = 0; j < 3; j++) {
                float value = src.getFloat(48 + i * 12 + j * 4);
                if (!Float.isFinite(value)) { value = 0f; status &= ~(i == 2 ? 0x2 : 0x100); }
                dst.putFloat(EYE_VECTORS + i * 12 + j * 4, value);
            }
            if (i < 2) {
                float openness = src.getFloat(84 + i * 4), pupil = src.getFloat(92 + i * 4);
                if (!Float.isFinite(openness)) { openness = 0f; status &= ~0x4; }
                if (!Float.isFinite(pupil)) { pupil = 0f; status &= ~0x800; }
                dst.putFloat(EYE_OPENNESS + i * 4, openness);
                dst.putFloat(EYE_PUPIL + i * 4, pupil);
            }
            dst.putInt(EYE_STATUS + i * 4, status);
        }
        return packet;
    }

    /** Runtime forwarding filters each group's update bit against its own source clock. */
    static byte[] facePacket(byte[] face, int mask, long epoch, long now) {
        if (!faceFresh(face, now, mask)) return null;
        byte[] packet = facePacket(face, mask, epoch);
        if (independentVersion(face) == 2 && (mask & 1) != 0) {
            int count = mask == 1 ? EYE_SHAPES.length : FACE_SHAPE_COUNT;
            bytes(packet).putInt(HEADER_SIZE + count * 4 + 16, freshGroups(face, now, mask));
        }
        return packet;
    }

    /** Directly serializes only the subscribed morphology from a >=384-byte vendor facial prefix. */
    static byte[] facePacket(byte[] face, int mask, long epoch) {
        if (face == null || face.length < 384) throw new IllegalArgumentException("Facial prefix too short");
        if (mask < 1 || mask > 3) throw new IllegalArgumentException("Unsupported channel mask");
        int[] shapes = mask == 1 ? EYE_SHAPES : mask == 2 ? FACE_ONLY_SHAPES : null;
        int count = shapes == null ? FACE_SHAPE_COUNT : shapes.length;
        ByteBuffer src = bytes(face);
        int version = independentVersion(face);
        boolean independent = (mask & 1) != 0 && version != 0;
        int extension = HEADER_SIZE + count * 4;
        byte[] packet = new byte[extension + (independent ? INDEPENDENT_EXTENSION_SIZE + (version == 2 ? 20 : 0) : 0)];
        ByteBuffer dst = bytes(packet);
        int valid = ((src.getFloat(296) == 1f ? 1 : 0) | (src.getFloat(300) == 1f ? 2 : 0)) & mask;
        header(dst, independent ? INDEPENDENT_TAG : mask == 1 ? AUX_TAG : FACE_TAG, mask, valid, epoch,
                version == 2 && mask == 2 ? src.getLong(240) : src.getLong(0));
        for (int i = 0; i < count; i++) {
            float value = src.getFloat(8 + (shapes == null ? i : shapes[i]) * 4);
            // No per-shape presence bits: zero a single non-finite sentinel instead of
            // invalidating the whole eye/face group, so one bad slot cannot blank the frame.
            dst.putFloat(HEADER_SIZE + i * 4, Float.isFinite(value) ? value : 0f);
        }
        if (independent) {
            float rightInner = src.getFloat(216);
            boolean rightFinite = Float.isFinite(rightInner);
            dst.putInt(extension, version);
            dst.putFloat(extension + 4, rightFinite ? rightInner : 0f);
            dst.putInt(extension + 8, src.getInt(220));
            dst.putInt(extension + 12, rightFinite ? src.getInt(224) : 0);
            if (!rightFinite && src.getInt(220) == 0) dst.put(HEADER_VALID, (byte) (valid & ~1));
            if (version == 2) {
                // EPI1: left source remains at 0; updates L/R/mouth at 236, mouth/right at 240/248.
                dst.putInt(extension + 16, src.getInt(236) & (mask == 1 ? 3 : 7));
                dst.putLong(extension + 20, src.getLong(240));
                dst.putLong(extension + 28, src.getLong(248));
            }
        }
        return packet;
    }

    // The receiver picks per-eye gaze solely from 0x100 (EYE_GAZE_VECTOR_VALID) and reads pupil
    // diameter without any validity check. Some firmware reports a fixed-depth split of the fused
    // gaze as its "per-eye" vectors rather than independent per-eye data; forwarding that makes the
    // receiver hold a constant offset between the two eyes. Strip the per-eye gaze point/vector
    // validity bits on the wire so the receiver keeps using the fused gaze while still reading real
    // pupil diameter. No-op when those bits are not set. In enhance mode with the dual plugin the
    // per-eye vectors are real, so the caller asks to keep them.
    private static final int STRIP_PER_EYE_GAZE_FLAGS = 0x80 | 0x100; // EYE_GAZE_POINT_VALID | EYE_GAZE_VECTOR_VALID

    private TrackingData() {}

    static ByteBuffer bytes(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
    public static long eyeTimestamp(byte[] eye) { return bytes(eye).getLong(168); }
    public static long faceTimestamp(byte[] face) { return bytes(face).getLong(0); }
    static long packetTimestamp(byte[] packet) { return bytes(packet).getLong(HEADER_TIMESTAMP); }
    static long facePacketTimestamp(byte[] packet) {
        if ((packet[1] & 2) == 0) return 0;
        if (packet[0] == FACE_TAG) return packetTimestamp(packet);
        if (packet[0] != INDEPENDENT_TAG) return 0;
        ByteBuffer src = bytes(packet);
        int extension = HEADER_SIZE + FACE_SHAPE_COUNT * 4;
        if (src.getInt(extension) != 2) return packetTimestamp(packet);
        return (src.getInt(extension + 16) & 4) != 0 ? src.getLong(extension + 20) : 0;
    }
    static int independentVersion(byte[] face) {
        ByteBuffer src = bytes(face);
        int version = src.getInt(232);
        return src.getInt(228) == INDEPENDENT_MAGIC && (version == 1 || version == 2) ? version : 0;
    }
    public static float combinedGazeX(byte[] eye) { return bytes(eye).getFloat(72); }
    public static boolean eyeValid(byte[] eye) {
        if (eye == null || eye.length < 200) return false;
        ByteBuffer b = bytes(eye);
        return (b.getInt(8) & 2) != 0 && Float.isFinite(b.getFloat(72)) && Float.isFinite(b.getFloat(76)) && Float.isFinite(b.getFloat(80));
    }
    public static boolean faceValid(byte[] face) {
        return face != null && face.length >= 384 && bytes(face).getFloat(300) == 1f;
    }
    public static boolean fresh(long now, long timestamp) {
        return timestamp > 0 && now - timestamp >= -100000000L && now - timestamp < 2000000000L;
    }
    public static boolean forwardable(byte[] face, byte[] eye, long now) {
        // Invalid gaze during a blink must not suppress valid facial blendshapes or their validity flags.
        return face != null && eye != null && face.length >= 384 && eye.length >= 200
                && fresh(now, eyeTimestamp(eye)) && fresh(now, faceTimestamp(face));
    }
    static boolean eyeFresh(byte[] eye, long now) {
        return eye != null && eye.length >= 200 && fresh(now, eyeTimestamp(eye));
    }
    static boolean faceFresh(byte[] face, long now) {
        return faceFresh(face, now, 3);
    }
    static boolean faceFresh(byte[] face, long now, int mask) {
        if (face == null || face.length < 384) return false;
        if (independentVersion(face) != 2) return fresh(now, faceTimestamp(face));
        return freshGroups(face, now, mask) != 0;
    }
    private static int freshGroups(byte[] face, long now, int mask) {
        ByteBuffer src = bytes(face);
        int updated = src.getInt(236) & (mask == 1 ? 3 : mask == 2 ? 4 : 7);
        if (!fresh(now, src.getLong(0))) updated &= ~1;
        if (!fresh(now, src.getLong(248))) updated &= ~2;
        if (!fresh(now, src.getLong(240))) updated &= ~4;
        return updated;
    }
    static boolean forwardable(byte[] face, byte[] eye, long now, boolean sendEye, boolean sendFace) {
        return (sendEye && eyeFresh(eye, now)) || (sendFace && faceFresh(face, now));
    }
}
