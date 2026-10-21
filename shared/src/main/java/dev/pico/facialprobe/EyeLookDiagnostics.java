package dev.pico.facialprobe;

/** Opt-in, bounded local diagnostic; no allocation of value strings while disabled. */
final class EyeLookDiagnostics {
    private static final int[] SLOTS = {45,11,35,12,44,2,31,0};
    private long next;
    String sample(boolean enabled, long now, DirectFaceMapper mapper, byte[] packet, int mask) {
        if (!enabled || now < next) return null;
        next = now + 500_000_000L;
        StringBuilder s = new StringBuilder(420);
        s.append("EYE_LOOK source=").append(mapper.sourceName()).append(" mask=").append(mask)
                .append(" latestUsableL/R=").append(mapper.latestUsable(0)).append('/').append(mapper.latestUsable(1))
                .append(" observationAgeMsL/R=").append(mapper.observationAgeMs(0,now)).append('/').append(mapper.observationAgeMs(1,now))
                .append(" validAgeMsL/R=").append(mapper.validAgeMs(0,now)).append('/').append(mapper.validAgeMs(1,now))
                .append(" usedRaw[45,11,35,12,44,2,31,0]=");
        for (int i=0;i<SLOTS.length;i++) { if(i!=0)s.append('/'); s.append(mapper.usedRaw(SLOTS[i])); }
        s.append(" blob0to7=");
        for (int i=0;i<8;i++) { if(i!=0)s.append('/'); s.append(packet[28+i]&255); }
        return s.append(" receiver=unconfirmed").toString();
    }
}
