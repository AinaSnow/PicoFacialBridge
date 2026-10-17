package dev.pico.facialprobe;

/** Worker-owned five-second counters. Counts observations, not coefficient changes. */
final class XDeliveryStats {
    private long epoch, left, right, mouth, packets, v1, v2, leftNew, rightNew, mouthNew;
    private long started;

    void sent(byte[] payload, long now) {
        if (started == 0) started = now;
        if (payload[0] != TrackingData.INDEPENDENT_TAG) return;
        java.nio.ByteBuffer data = TrackingData.bytes(payload);
        long nextEpoch = data.getLong(3);
        if (epoch != nextEpoch) { epoch = nextEpoch; left = right = mouth = 0; }
        int offset = TrackingData.HEADER_SIZE + (payload[1] == 1 ? 19 : 52) * 4;
        int version = data.getInt(offset);
        packets++;
        if (version == 1) v1++; else v2++;
        int updated = version == 2 ? data.getInt(offset + 16) : payload[1] == 1 ? 3 : 7;
        long leftSource = data.getLong(11);
        long rightSource = version == 2 ? data.getLong(offset + 28) : leftSource;
        long mouthSource = version == 2 ? data.getLong(offset + 20) : leftSource;
        if ((updated & 1) != 0 && leftSource > left) { leftNew++; left = leftSource; }
        if ((updated & 2) != 0 && rightSource > right) { rightNew++; right = rightSource; }
        if ((updated & 4) != 0 && mouthSource > mouth) { mouthNew++; mouth = mouthSource; }
    }

    String report(long now) {
        double seconds = started == 0 ? 0 : (now - started) / 1e9;
        String result = "X_DELIVERY windowMs=" + Math.round(seconds * 1000) + " packets=" + packets +
                " v1=" + v1 + " v2=" + v2 + " distinctLRM=" + leftNew + "/" + rightNew + "/" + mouthNew +
                " xTxHz=" + (seconds > 0 ? packets / seconds : 0) +
                " sourceHzLRM=" + (seconds > 0 ? leftNew / seconds : 0) + "/" +
                (seconds > 0 ? rightNew / seconds : 0) + "/" + (seconds > 0 ? mouthNew / seconds : 0);
        packets = v1 = v2 = leftNew = rightNew = mouthNew = 0;
        started = now;
        return result;
    }
}
