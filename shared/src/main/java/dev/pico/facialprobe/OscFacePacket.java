package dev.pico.facialprobe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** OSC 1.0 message, one 102-byte blob. Owned and reused by the sending thread. */
final class OscFacePacket {
    static final int KEY_COUNT = 102, BLOB_OFFSET = 28, SIZE = 132;
    final byte[] bytes = new byte[SIZE];

    OscFacePacket() {
        byte[] address = "/tracking/face/v1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(address, 0, bytes, 0, address.length);
        bytes[20] = ','; bytes[21] = 'b';
        // OSC int32 length is big-endian; address, type tag and blob are padded to 4 bytes.
        bytes[27] = KEY_COUNT;
    }

    void encode(float[] values) {
        if (values.length != KEY_COUNT) throw new IllegalArgumentException("Expected 102 keys");
        for (int i = 0; i < KEY_COUNT; i++) bytes[BLOB_OFFSET + i] = quantize(values[i]);
    }

    void neutral() { Arrays.fill(bytes, BLOB_OFFSET, SIZE, (byte) 0); }

    static byte quantize(float value) {
        return (byte) (Float.isFinite(value) ? (int) (Math.max(0f, Math.min(1f, value)) * 255f) : 0);
    }
}
