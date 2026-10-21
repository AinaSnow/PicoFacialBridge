package dev.pico.facialprobe;

import java.util.Arrays;

/**
 * PICO morphology -> VRChat wire order (not the module enum order).
 * Mapping adapted from UnifiedPicoModule, Copyright (c) 2026 UnifiedPicoModule contributors,
 * MIT license; see THIRD_PARTY_NOTICES.md. No PC module or VRCFT runtime is used.
 * Single-threaded, fixed storage. Each EPI1 group has its own clock and last valid state.
 */
final class DirectFaceMapper {
    static final long STALE_NS = 2_000_000_000L;
    private static final int[] LEFT = {0,2,3,16,26,28,30,31,44,47};
    private static final int[] RIGHT = {4,11,12,35,36,38,41,45,46};
    private static final int[] MOUTH = {1,5,6,7,8,9,10,13,14,15,17,18,19,20,21,22,23,24,25,27,29,32,33,34,37,39,40,42,43,48,49,50,51};
    // Each row: source slot followed by destination wire indices.
    private static final int[][] BINDINGS = {
        {41,14},{26,15},{46,16},{47,17},{4,18,20},{16,19,21},{36,24},{30,25},
        {37,30},{29,31},{43,32,33},{7,36},{15,37},{32,38},{25,39},{5,43},
        {14,44,45},{18,46,47},{10,50,51,52,53},{34,54,55,56,57},
        {8,58,60},{39,59,61},{13,62},{1,63},{6,64},{50,65},{48,66,68},{24,67,69},
        {21,70,72},{19,71,73},{40,74},{27,75},{42,76},{33,77},{23,78},{49,79},
        {9,80},{17,81},{22,82},{20,83},{51,86}
    };
    private final float[] raw = new float[52];
    final float[] values = new float[OscFacePacket.KEY_COUNT];
    private final long[] seen = new long[3], validAt = new long[3];
    private float rightInner;
    private int version = -1;
    private final BridgeDiagnostics diagnostics;

    DirectFaceMapper() { this(null); }
    DirectFaceMapper(BridgeDiagnostics diagnostics) { this.diagnostics = diagnostics; }

    void reset() {
        if (diagnostics != null) diagnostics.reset();
        Arrays.fill(raw, 0); Arrays.fill(values, 0);
        Arrays.fill(seen, 0); Arrays.fill(validAt, 0);
        rightInner = 0; version = -1;
    }

    /** Missing/short frames do not overwrite other groups. Timestamp unit is PICO monotonic ns. */
    void accept(byte[] face, long now) {
        if (face == null || face.length < 384) { if (diagnostics != null) diagnostics.malformed(); return; }
        int v = i32(face, 228) == 0x31495045 ? i32(face, 232) : 0;
        if (v != 1 && v != 2) v = 0;
        if (version != v) { reset(); version = v; }
        int updates = v == 2 ? i32(face, 236) : 7;
        for (int group = 0; group < 3; group++) {
            long time = i64(face, v == 2 ? (group == 1 ? 248 : group == 2 ? 240 : 0) : 0);
            if ((updates & (1 << group)) == 0) continue;
            if (time <= seen[group]) { if (diagnostics != null) diagnostics.duplicate(); continue; }
            if (!fresh(now, time)) { if (diagnostics != null) diagnostics.clockRejected(); continue; }
            seen[group] = time;
            boolean usable = group == 2 ? f32(face, 300) == 1f
                    : v == 0 ? f32(face, 296) == 1f : i32(face, group == 0 ? 220 : 224) != 0;
            if (!usable) { if (diagnostics != null) diagnostics.invalid(group); continue; }
            int[] slots = group == 0 ? LEFT : group == 1 ? RIGHT : MOUTH;
            for (int slot : slots) raw[slot] = sourceValue(f32(face, 8 + slot * 4));
            if (group == 1) rightInner = sourceValue(f32(face, v == 0 ? 20 : 216));
            validAt[group] = time;
            if (diagnostics != null) diagnostics.valid(group);
        }
    }

    /** Full snapshot; unsupported keys (including pupil 10..13) remain zero. */
    float[] snapshot(long now, int mask) {
        if (diagnostics != null) diagnostics.snapshot();
        for (int group = 0; group < 3; group++) {
            if (!fresh(now, validAt[group]) || (mask & (group == 2 ? 2 : 1)) == 0) {
                int[] slots = group == 0 ? LEFT : group == 1 ? RIGHT : MOUTH;
                for (int slot : slots) raw[slot] = 0;
                if (group == 1) rightInner = 0;
            }
        }
        Arrays.fill(values, 0);
        for (int[] binding : BINDINGS) {
            float value = clamp(raw[binding[0]]);
            if (binding[0] == 46 || binding[0] == 47) value = clamp(value * 2);
            for (int j = 1; j < binding.length; j++) values[binding[j]] = value;
        }
        // EyeResolver convention: right x=out-in; left x=in-out, then wire left is inverted.
        split(raw[45] - raw[11], 0, 1); split(raw[35] - raw[12], 2, 3);
        split(raw[44] - raw[2], 4, 5); split(raw[31] - raw[0], 6, 7);
        values[8] = clamp(raw[38]); values[9] = clamp(raw[28]);
        values[22] = clamp(rightInner); values[23] = clamp(raw[3]);
        return values;
    }

    boolean eyeFresh(long now) { return fresh(now, validAt[0]) || fresh(now, validAt[1]); }
    boolean mouthFresh(long now) { return fresh(now, validAt[2]); }
    long eyeTime() { return Math.max(validAt[0], validAt[1]); }
    long mouthTime() { return validAt[2]; }
    long validTime(int group) { return validAt[group]; }
    boolean groupFresh(int group, long now) { return fresh(now, validAt[group]); }
    long validAgeMs(int group, long now) { return validAt[group] == 0 ? -1 : Math.max(0,(now-validAt[group])/1_000_000L); }
    String sourceName() { return version < 0 ? "none" : version == 0 ? "normal-morphology" : version == 1 ? "EPI1-v1" : "EPI1-v2"; }
    int sourceVersion() { return version; }
    private float sourceValue(float v) { if (diagnostics != null) diagnostics.value(v); return finite(v); }
    private void split(float difference, int positive, int negative) {
        values[positive] = clamp(difference); values[negative] = clamp(-difference);
    }
    private static float finite(float v) { return Float.isFinite(v) ? v : 0; }
    private static float clamp(float v) { return Math.max(0, Math.min(1, finite(v))); }
    private static boolean fresh(long now, long time) {
        return time > 0 && now - time >= -100_000_000L && now - time < STALE_NS;
    }
    private static int i32(byte[] b, int p) {
        return (b[p] & 255) | ((b[p+1] & 255) << 8) | ((b[p+2] & 255) << 16) | (b[p+3] << 24);
    }
    private static long i64(byte[] b, int p) {
        return Integer.toUnsignedLong(i32(b,p)) | ((long)i32(b,p+4) << 32);
    }
    private static float f32(byte[] b, int p) { return Float.intBitsToFloat(i32(b,p)); }
}
