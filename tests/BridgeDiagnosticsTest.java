package dev.pico.facialprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Counter semantics, throttling and privacy tested without Android or a headset. */
public final class BridgeDiagnosticsTest {
    private static int checks;
    private static final long T=10_000_000_000L;
    private static void check(boolean condition,String why) { checks++; if(!condition)throw new AssertionError(why); }
    private static void has(String text,String expected) { check(text!=null&&text.contains(expected),"Expected "+expected+" in "+text); }
    private static ByteBuffer frame(long time,int updates) {
        return ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).putLong(0,time)
                .putLong(248,time).putLong(240,time).putInt(228,0x31495045).putInt(232,2)
                .putInt(236,updates).putInt(220,1).putInt(224,1).putFloat(300,1);
    }
    private static void counters() {
        BridgeDiagnostics d=new BridgeDiagnostics(T);DirectFaceMapper m=new DirectFaceMapper(d);
        check(!d.due(T+4_999_999_999L),"five-second gate");check(d.due(T+5_000_000_000L),"summary due");
        d.captured(3,2,T);d.captured(0,0,T+1);
        ByteBuffer f=frame(T,7).putFloat(8+7*4,.75f).putFloat(8+28*4,Float.NaN).putFloat(8+38*4,2);
        m.accept(f.array(),T);m.accept(f.array(),T); // Three duplicate groups, not valid samples.
        ByteBuffer left=frame(T+1,1).putFloat(8+28*4,.25f);m.accept(left.array(),T+1);
        check(m.snapshot(T+1,3)[36]==.75f,"diagnostics preserves held mouth");
        d.sent(false);d.sent(true);
        ByteBuffer bad=frame(T+2,7).putInt(220,0).putInt(224,0).putFloat(300,0);m.accept(bad.array(),T+2);
        m.accept(new byte[2],T+3);m.accept(frame(T+4_000_000_000L,7).array(),T); // Future clocks.
        d.reconnect();
        String summary=d.summary(T+5_000_000_000L,m,3,"192.168.1.10:9000",60,true,4);
        has(summary,"windowSec=5.000");has(summary,"ringReadHzE/F=0.6/0.4");
        has(summary,"validGroupHzL/R/M=0.4/0.2/0.2");has(summary,"invalidGroupsL/R/M=1/1/1");
        has(summary,"emptyReadBatchesE/F=1/1");has(summary,"snapshotHz=0.2 udpSendOkHz=0.4");
        has(summary,"udpOkTotal=2 neutralOk=1");has(summary,"supersededBeforeSnapshot=1");
        has(summary,"ringOverrunsTotal=4 duplicateGroups=3 clockRejectedGroups=3");
        has(summary,"malformedFrames=1 nonFiniteFieldReadsZeroed=1 sourceFieldReadsOutside01=1");
        has(summary,"source=EPI1-v2");has(summary,"receiver=unconfirmed");
        check(!summary.contains("JawOpen")&&!summary.contains("0.75"),"no expression samples in default logs");
        String next=d.summary(T+10_000_000_000L,m,3,"192.168.1.10:9000",60,true,4);
        has(next,"ringReadHzE/F=0.0/0.0");has(next,"invalidGroupsL/R/M=0/0/0");
        has(next,"udpOkTotal=2 neutralOk=0");has(next,"supersededBeforeSnapshot=0");
        check(!d.due(T+10_000_000_001L),"window restarted");
        m.reset();check(m.validAgeMs(0,T)==-1,"unknown source age");
        check(m.sourceName().equals("none"),"reset source name");
    }
    private static void errorsAndHealth() {
        BridgeDiagnostics d=new BridgeDiagnostics(T);DirectFaceMapper m=new DirectFaceMapper(d);
        Throwable error=new IllegalStateException("test failure");
        has(d.error(BridgeDiagnostics.UDP,error,T),"count=1 suppressedSinceLog=0");
        for(int i=0;i<100;i++) check(d.error(BridgeDiagnostics.UDP,error,T+i+1)==null,"repeat exception throttled");
        has(d.error(BridgeDiagnostics.OPEN,error,T+1),"kind=sessionOpen count=1");
        has(d.error(BridgeDiagnostics.UDP,error,T+5_000_000_000L),"count=102 suppressedSinceLog=100");
        String s=d.summary(T+5_000_000_000L,m,3,"192.168.1.10:9000",60,false,0);
        has(s,"errorsTotalOpen/Read/Udp=1/0/102");
        has(d.health(T,m,3,false,false),"cacheL/R/M=waiting/waiting/waiting");
        m.accept(frame(T+1,7).array(),T+1);d.captured(1,1,T+1);
        check(d.health(T+1,m,3,true,false)==null,"state change throttled");
        has(d.health(T+1_000_000_000L,m,3,true,false),"cacheL/R/M=fresh/fresh/fresh");
        check(d.health(T+1_000_000_001L,m,3,true,false)==null,"identical state suppressed");
        m.accept(frame(T+1_000_000_002L,1).putInt(220,0).array(),T+1_000_000_002L);
        has(d.health(T+2_000_000_000L,m,3,true,true),"latestGroupUsableL/R/M=0/1/1");
        has(d.health(T+3_000_000_000L,m,3,true,false),"nativeEye=noNewFor1s morphology=noNewFor1s");
        has(d.health(T+4_000_000_000L,m,0,false,false),"cacheL/R/M=disabled/disabled/disabled");
        m.reset();m.accept(frame(T+5_000_000_000L,7).array(),T+5_000_000_000L);d.captured(1,1,T+5_000_000_000L);
        has(d.health(T+5_000_000_000L,m,3,true,false),"nativeEye=updates morphology=updates");
        check(m.validAgeMs(0,T+4_999_999_999L)==0,"accepted slight future source age clamped to zero");
    }
    public static void main(String[] args) { counters();errorsAndHealth();System.out.println("PASS: BridgeDiagnosticsTest "+checks+" counter/throttle/privacy checks"); }
}
