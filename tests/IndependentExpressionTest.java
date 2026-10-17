package dev.pico.facialprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** EPI1 / BridgeSplit X: v1 framing, v2 per-group clocks, grouped ring reads and delivery counters. */
public final class IndependentExpressionTest {
    private static int checks;
    private static final long SECOND = 1_000_000_000L, MS = 1_000_000L;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    /** 896-byte facial prefix with an EPI1 extension; version 1 ignores the group fields. */
    private static byte[] frame(int version, long left, long right, long mouth, int updated) {
        byte[] b = new byte[896]; ByteBuffer v = TrackingData.bytes(b);
        v.putLong(0, left); v.putFloat(296, 1); v.putFloat(300, 1);
        v.putFloat(216, .25f); v.putInt(220, 1); v.putInt(224, 1);
        v.putInt(228, 0x31495045); v.putInt(232, version);
        if (version == 2) { v.putInt(236, updated); v.putLong(240, mouth); v.putLong(248, right); }
        return b;
    }
    private static int updated(byte[] packet, int mask) { return TrackingData.bytes(packet).getInt(19 + (mask == 1 ? 19 : 52) * 4 + 16); }
    private static void framing() {
        long now = 10 * SECOND;
        byte[] v1 = TrackingData.facePacket(frame(1, now, 0, 0, 0), 1, 7);
        check(v1.length == 111 && v1[0] == 'X', "v1 eye-only X stays 111 bytes");
        check(TrackingData.facePacket(frame(1, now, 0, 0, 0), 3, 7).length == 243, "v1 dual X stays 243 bytes");
        byte[] eyeOnly = TrackingData.facePacket(frame(2, now, now + 1, now + 2, 7), 1, 7, now + 3);
        check(eyeOnly.length == 131 && eyeOnly[0] == 'X' && eyeOnly[1] == 1, "v2 eye-only X is 131 bytes");
        check(updated(eyeOnly, 1) == 3, "Eye-only v2 cannot carry the mouth update bit");
        byte[] dual = TrackingData.facePacket(frame(2, now, now + 1, now + 2, 7), 3, 7, now + 3);
        ByteBuffer d = TrackingData.bytes(dual);
        check(dual.length == 263 && dual[0] == 'X' && d.getInt(227) == 2, "v2 dual X is 263 bytes with version 2");
        check(updated(dual, 3) == 7 && d.getLong(11) == now, "Header timestamp is the left-eye source");
        check(d.getLong(247) == now + 2 && d.getLong(255) == now + 1, "Extension carries mouth then right-eye source time");
        byte[] faceOnly = TrackingData.facePacket(frame(2, now, now + 1, now + 2, 7), 2, 7, now + 3);
        check(faceOnly.length == 151 && faceOnly[0] == 'F', "Face-only subscription never gets the X extension");
        check(TrackingData.bytes(faceOnly).getLong(11) == now + 2, "Face-only v2 uses the mouth source time");
        check(TrackingData.independentVersion(frame(3, now, 0, 0, 0)) == 0, "Unknown EPI1 version is not independent");
    }
    private static void freshness() {
        long now = 10 * SECOND;
        byte[] packet = TrackingData.facePacket(frame(2, now, now, now - 3 * SECOND, 7), 3, 7, now);
        check(packet != null && updated(packet, 3) == 3, "A stale mouth loses only its own update bit");
        packet = TrackingData.facePacket(frame(2, now - 3 * SECOND, now, now, 7), 3, 7, now);
        check(packet != null && updated(packet, 3) == 6, "A stale left eye loses only bit 0");
        check(TrackingData.facePacket(frame(2, now - 3 * SECOND, now - 3 * SECOND, now - 3 * SECOND, 7), 3, 7, now) == null,
                "All groups stale emits nothing");
        check(TrackingData.facePacket(frame(2, now, now, now, 0), 3, 7, now) == null, "Update mask 0 emits nothing");
        check(TrackingData.facePacket(frame(2, now, now, now, 4), 1, 7, now) == null, "Mouth-only update is not sent to eye-only");
        check(TrackingData.facePacketTimestamp(TrackingData.facePacket(frame(2, now, now, now + MS, 7), 3, 7, now)) == now + MS,
                "Face-rate timestamp of an X packet is the mouth source");
        check(TrackingData.facePacketTimestamp(TrackingData.facePacket(frame(2, now, now, now + MS, 3), 3, 7, now)) == 0,
                "An eye-only update is not a face observation");
        check(TrackingData.facePacketTimestamp(TrackingData.facePacket(frame(2, now, now, now, 7), 1, 7, now)) == 0,
                "Eye-only subscription has no face observation");
    }
    private static void pump() throws Exception {
        long now = 10 * SECOND;
        FramePump pump = new FramePump(); List<byte[]> sent = new ArrayList<>();
        pump.accept(Collections.emptyList(), Collections.singletonList(frame(2, now, now, now, 3)), now, true, true, sent::add);
        check(sent.size() == 1 && updated(sent.get(0), 3) == 3, "Pump forwards an eye-only update");
        check(!pump.faceValid(now), "Mouth is not valid before a mouth observation");
        check(pump.morphologyFresh(now, 1) && !pump.morphologyFresh(now, 2), "Eye group fresh, mouth group not yet observed");
        pump.accept(Collections.emptyList(), Collections.singletonList(frame(2, now + 10 * MS, now + 10 * MS, now + 10 * MS, 4)),
                now + 10 * MS, true, true, sent::add);
        check(sent.size() == 2 && updated(sent.get(1), 3) == 4, "Pump forwards a mouth-only update");
        check(pump.faceValid(now + 20 * MS) && pump.morphologyFresh(now + 20 * MS, 3), "Mouth valid and all groups fresh");
        check(!pump.faceValid(now + 3 * SECOND) && !pump.morphologyFresh(now + 3 * SECOND, 3), "Held groups expire");
        pump.accept(Collections.emptyList(), Collections.singletonList(frame(1, now + 4 * SECOND, 0, 0, 0)),
                now + 4 * SECOND, true, true, sent::add);
        check(pump.faceValid(now + 4 * SECOND), "A v1 frame has one clock, so its mouth is a new observation");
        pump.accept(Collections.emptyList(), Collections.singletonList(frame(2, now + 5 * SECOND, now + 5 * SECOND, 0, 3)),
                now + 5 * SECOND, true, true, sent::add);
        check(!pump.faceValid(now + 5 * SECOND), "Switching EPI1 version clears the cached mouth state");
    }
    private static ByteBuffer ring(int capacity) {
        ByteBuffer b = ByteBuffer.allocate(20 + 896 * capacity).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 1); b.putInt(4, 896); b.putInt(8, capacity); b.putInt(12, -1); b.putInt(16, 20);
        return b;
    }
    private static void publish(ByteBuffer ring, int slot, byte[] frame) {
        ring.position(20 + 896 * slot); ring.put(frame); ring.putInt(12, slot);
    }
    private static int pending(byte[] sample) { return TrackingData.bytes(sample).getInt(236); }
    private static void groupedCursor() {
        ByteBuffer memory = ring(4);
        TrackingBuffer.Cursor cursor = new TrackingBuffer.Cursor(memory, 384, 0);
        check(cursor.read().isEmpty(), "Empty grouped ring reads nothing");
        publish(memory, 0, frame(2, 100, 100, 100, 7));
        List<byte[]> read = cursor.read();
        check(read.size() == 1 && pending(read.get(0)) == 7, "First grouped snapshot reports all groups");
        check(cursor.read().isEmpty(), "Unchanged ring reports nothing");
        publish(memory, 1, frame(2, 111, 111, 100, 3));
        read = cursor.read();
        check(read.size() == 1 && pending(read.get(0)) == 3, "Eye update does not re-report the held mouth");
        publish(memory, 2, frame(2, 111, 111, 124, 4));
        read = cursor.read();
        check(read.size() == 1 && pending(read.get(0)) == 4, "Mouth-only update is read although the left clock is unchanged");
        publish(memory, 3, frame(2, 122, 122, 124, 3));
        publish(memory, 0, frame(2, 133, 133, 124, 3));
        read = cursor.read();
        check(read.size() == 2 && TrackingData.bytes(read.get(0)).getLong(0) == 122 &&
                TrackingData.bytes(read.get(1)).getLong(0) == 133, "Several updates between reads are returned oldest first");
        check(cursor.overruns() == 0, "No overrun with retained history");
        publish(memory, 1, frame(2, 144, 144, 124, 3));
        publish(memory, 2, frame(2, 155, 155, 124, 3));
        publish(memory, 3, frame(2, 166, 166, 124, 3));
        publish(memory, 0, frame(2, 177, 177, 124, 3));
        publish(memory, 1, frame(2, 188, 188, 124, 3));
        publish(memory, 2, frame(2, 199, 199, 124, 3));
        cursor.read();
        check(cursor.overruns() == 1, "Updates lost to ring wrap are counted");
    }
    private static void deliveryStats() {
        long now = 10 * SECOND;
        XDeliveryStats stats = new XDeliveryStats();
        stats.sent(TrackingData.facePacket(frame(2, now, now, now, 7), 3, 5, now), 1 * SECOND);
        stats.sent(TrackingData.facePacket(frame(2, now + 11 * MS, now + 11 * MS, now, 3), 3, 5, now + 11 * MS), 2 * SECOND);
        stats.sent(TrackingData.facePacket(frame(2, now + 11 * MS, now + 11 * MS, now, 3), 3, 5, now + 11 * MS), 3 * SECOND);
        stats.sent(TrackingData.facePacket(frame(1, now + 22 * MS, 0, 0, 0), 1, 5), 4 * SECOND);
        stats.sent(TrackingData.eyePacket(CadenceTest.eye(now, .1f), false, 1, 5), 5 * SECOND);
        String report = stats.report(6 * SECOND);
        check(report.contains("packets=4 v1=1 v2=3"), "Counts X packets by version: " + report);
        check(report.contains("distinctLRM=3/3/1"), "v1 has one clock, so its packet advances both eyes: " + report);
        check(stats.report(7 * SECOND).contains("packets=0"), "Counters reset after each report");
    }
    public static void main(String[] args) throws Exception {
        framing(); freshness(); pump(); groupedCursor(); deliveryStats();
        System.out.println("PASS: " + checks + " independent-expression X v1/v2 checks");
    }
}
