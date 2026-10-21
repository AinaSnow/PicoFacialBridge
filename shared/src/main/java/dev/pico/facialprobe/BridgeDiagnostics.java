package dev.pico.facialprobe;

import java.util.Locale;

/** Fixed-storage counters. Formatting happens only at summary/state/error boundaries. */
final class BridgeDiagnostics {
    static final long SUMMARY_NS = 5_000_000_000L;
    static final int OPEN = 0, READ = 1, UDP = 2;
    private final long[] valid = new long[3], invalid = new long[3], pending = new long[3];
    private final int[] latestUsable = {-1,-1,-1};
    private final long[] errorLast = {-SUMMARY_NS, -SUMMARY_NS, -SUMMARY_NS};
    private final long[] errorTotals = new long[3], errorSuppressed = new long[3];
    private long windowStart, eyeRead, faceRead, emptyEyeReads, emptyFaceReads, snapshots, sent, neutralSent;
    private long lastEyeRead = -1, lastFaceRead = -1, superseded, clockRejected, duplicateGroups, malformed;
    private long sanitized, outOfRange, reconnects, resetCount, totalSent, healthChanges, nextHealthLog;
    private int observedHealth = -1, loggedHealth = -1;

    BridgeDiagnostics(long now) { windowStart = now; }
    void captured(int eyes, int faces, long now) {
        eyeRead += eyes; faceRead += faces;
        if (eyes > 0) lastEyeRead = now; else emptyEyeReads++;
        if (faces > 0) lastFaceRead = now; else emptyFaceReads++;
    }
    void valid(int group) { valid[group]++; pending[group]++; latestUsable[group]=1; }
    void invalid(int group) { invalid[group]++; latestUsable[group]=0; }
    void clockRejected() { clockRejected++; }
    void duplicate() { duplicateGroups++; }
    void malformed() { malformed++; }
    void value(float value) {
        if (!Float.isFinite(value)) sanitized++;
        else if (value < 0 || value > 1) outOfRange++;
    }
    void snapshot() {
        snapshots++;
        for (int i = 0; i < 3; i++) { superseded += Math.max(0, pending[i] - 1); pending[i] = 0; }
    }
    void reset() { resetCount++; for (int i=0;i<3;i++) { pending[i]=0; latestUsable[i]=-1; } }
    void sent(boolean neutral) { sent++; totalSent++; if (neutral) neutralSent++; }
    void reconnect() { reconnects++; }

    /** Error counts include every occurrence, even when the text is suppressed. */
    String error(int kind, Throwable error, long now) {
        errorTotals[kind]++;
        if (now - errorLast[kind] < SUMMARY_NS) { errorSuppressed[kind]++; return null; }
        errorLast[kind] = now;
        String message = "ERROR kind=" + errorName(kind) + " count=" + errorTotals[kind]
                + " suppressedSinceLog=" + errorSuppressed[kind] + " exception=" + error;
        errorSuppressed[kind] = 0;
        return message;
    }
    private static String errorName(int kind) { return kind == OPEN ? "sessionOpen" : kind == READ ? "ringRead" : "udpSend"; }
    private static int sourceState(long last, long now) { return last < 0 ? 0 : now - last >= 1_000_000_000L ? 2 : 1; }
    private static int groupState(DirectFaceMapper mapper, int group, long now, int mask) {
        return (mask & (group == 2 ? 2 : 1)) == 0 ? 3 : mapper.validTime(group) == 0 ? 0
                : mapper.groupFresh(group, now) ? 1 : 2;
    }
    private static String groupName(int state) { return state == 0 ? "waiting" : state == 1 ? "fresh" : state == 2 ? "stale" : "disabled"; }
    private static String sourceName(int state) { return state == 0 ? "waiting" : state == 1 ? "updates" : "noNewFor1s"; }
    /** Coalesces flapping health states to at most one line per second; latest state is retained. */
    String health(long now, DirectFaceMapper mapper, int mask, boolean active, boolean udpError) {
        int e=sourceState(lastEyeRead,now), f=sourceState(lastFaceRead,now);
        int l=groupState(mapper,0,now,mask), r=groupState(mapper,1,now,mask), m=groupState(mapper,2,now,mask);
        int code=e | f<<2 | l<<4 | r<<6 | m<<8 | (active?1:0)<<10 | (udpError?1:0)<<11;
        for(int i=0;i<3;i++) code |= (latestUsable[i]+1) << (12+i*2);
        code |= (mapper.sourceVersion()+1)<<18;
        if (code != observedHealth) { healthChanges++; observedHealth=code; }
        if (code == loggedHealth || now < nextHealthLog) return null;
        loggedHealth=code; nextHealthLog=now+1_000_000_000L;
        return "HEALTH active="+active+" source="+mapper.sourceName()+" nativeEye="+sourceName(e)+" morphology="+sourceName(f)
                +" cacheL/R/M="+groupName(l)+"/"+groupName(r)+"/"+groupName(m)
                +" latestGroupUsableL/R/M="+latestUsable[0]+"/"+latestUsable[1]+"/"+latestUsable[2]
                +" mouthOutput="+((mask&2)==0?"disabled":mapper.latestUsable(2)==0?"neutralInvalid":mapper.mouthFresh(now)?"valid":"neutralNoValid")
                +" udp="+(udpError?"sendError":"noSendError")+" receiver=unconfirmed transitions="+healthChanges;
    }
    boolean due(long now) { return now - windowStart >= SUMMARY_NS; }
    String summary(long now, DirectFaceMapper mapper, int mask, String target, int hz, boolean enhanced, long ringOverruns) {
        double seconds=Math.max(1e-9,(now-windowStart)/1e9);
        String result=String.format(Locale.ROOT,
                "DIAG windowSec=%.3f target=%s configuredMaxHz=%d mask=%d enhanced=%s source=%s "
                +"ringReadHzE/F=%.1f/%.1f validGroupHzL/R/M=%.1f/%.1f/%.1f "
                +"invalidGroupsL/R/M=%d/%d/%d validAgeMsL/R/M=%d/%d/%d "
                +"lastReadAgeMsE/F=%d/%d emptyReadBatchesE/F=%d/%d "
                +"snapshotHz=%.1f udpSendOkHz=%.1f udpOkTotal=%d neutralOk=%d "
                +"supersededBeforeSnapshot=%d ringOverrunsTotal=%d duplicateGroups=%d clockRejectedGroups=%d "
                +"malformedFrames=%d nonFiniteFieldReadsZeroed=%d sourceFieldReadsOutside01=%d "
                +"errorsTotalOpen/Read/Udp=%d/%d/%d reconnects=%d cacheResets=%d receiver=unconfirmed",
                seconds,target,hz,mask,enhanced,mapper.sourceName(),eyeRead/seconds,faceRead/seconds,
                valid[0]/seconds,valid[1]/seconds,valid[2]/seconds,invalid[0],invalid[1],invalid[2],
                mapper.validAgeMs(0,now),mapper.validAgeMs(1,now),mapper.validAgeMs(2,now),
                age(lastEyeRead,now),age(lastFaceRead,now),emptyEyeReads,emptyFaceReads,
                snapshots/seconds,sent/seconds,totalSent,neutralSent,superseded,ringOverruns,duplicateGroups,
                clockRejected,malformed,sanitized,outOfRange,errorTotals[0],errorTotals[1],errorTotals[2],reconnects,resetCount);
        windowStart=now; eyeRead=faceRead=emptyEyeReads=emptyFaceReads=snapshots=sent=neutralSent=0;
        superseded=duplicateGroups=clockRejected=malformed=sanitized=outOfRange=reconnects=resetCount=0;
        for(int i=0;i<3;i++) valid[i]=invalid[i]=0;
        return result;
    }
    private static long age(long last,long now) { return last<0?-1:(now-last)/1_000_000L; }
}
