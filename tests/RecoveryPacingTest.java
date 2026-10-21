package dev.pico.facialprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Behavioral regressions for valid/invalid/stalled sources, scheduling and opt-in diagnostics. */
public final class RecoveryPacingTest {
    private static int checks;
    private static final long T=10_000_000_000L;
    private static void check(boolean b,String message) { checks++;if(!b)throw new AssertionError(message); }
    private static ByteBuffer frame(long t,int updates,boolean valid) {
        return ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN).putLong(0,t).putLong(248,t).putLong(240,t)
                .putInt(228,0x31495045).putInt(232,2).putInt(236,updates)
                .putInt(220,valid?1:0).putInt(224,valid?1:0).putFloat(300,valid?1:0);
    }
    private static void recovery() {
        DirectFaceMapper m=new DirectFaceMapper();long lastFresh=T/1_000_000;
        m.accept(frame(T,7,true).putFloat(8+7*4,.8f).putFloat(8+28*4,.7f).array(),T);
        m.accept(frame(T+1,4,false).array(),T+1);
        check(m.snapshot(T+1,3)[36]==0,"new invalid mouth immediately neutral");
        check(!m.mouthFresh(T+1),"invalid mouth not reported live");
        check(m.snapshot(T+1,3)[9]==.7f,"mouth invalid preserves eye");
        check(m.validAgeMs(2,T+1)==0,"last valid source age retained for diagnostics");
        m.accept(frame(T+2,4,true).putFloat(8+7*4,.6f).array(),T+2);
        check(m.mouthFresh(T+2)&&m.snapshot(T+2,3)[36]==.6f,"valid mouth recovers immediately");
        m.accept(frame(T+3,1,false).array(),T+3);
        check(m.snapshot(T+3,3)[9]==.7f,"invalid eye last-valid policy unchanged");
        check(m.snapshot(T+3,3)[36]==.6f,"missing independent mouth retained");
        for(int second=1;second<=30;second++) {
            long now=T+second*1_000_000_000L;
            m.accept(frame(now,7,false).array(),now);
            if(m.sourcesFresh(now,3))lastFresh=now/1_000_000;
            check(!StreamHealth.reconnectDue(true,true,now/1_000_000,lastFresh),"fresh invalid is not stalled");
        }
        long stop=T+30_000_000_000L;
        check(!m.sourcesFresh(stop+DirectFaceMapper.STALE_NS,3),"stopped source expires at boundary");
        check(!StreamHealth.reconnectDue(true,true,lastFresh+9999,lastFresh),"reconnect timeout boundary before");
        check(StreamHealth.reconnectDue(true,true,lastFresh+10000,lastFresh),"real source stall reconnects");
        check(StreamHealth.reconnectDue(true,false,lastFresh,lastFresh),"dead binder reconnects promptly");
        check(!StreamHealth.reconnectDue(false,false,lastFresh+20000,lastFresh),"inactive not repeatedly reconnected");
        m.reset();check(!m.sourcesFresh(stop,3),"reset clears observation identity");
        m.accept(frame(stop+1,7,true).array(),stop+1);check(m.sourcesFresh(stop+1,3),"reopened source recovers");
        m.accept(frame(stop+3_000_000_000L,1,true).array(),stop+3_000_000_000L);
        check(!m.sourcesFresh(stop+3_000_000_000L,1),"one frozen eye cannot hide behind other advancing eye");
        m.reset();m.accept(frame(stop,4,false).array(),stop);
        check(m.sourcesFresh(stop,2),"mouth-only invalid source is live without eyes");
        check(!m.sourcesFresh(stop,0),"all disabled not source activity");
        m.accept(frame(stop+10_000_000_000L,7,true).array(),stop);
        check(!m.sourcesFresh(stop,3),"rejected future timestamps do not fake activity");
    }
    private static void pacing() {
        for(int hz:new int[]{30,60,90,120}) {
            long now=T;DeadlinePacer p=new DeadlinePacer(hz,now);
            for(int i=0;i<hz*10;i++) {
                now+=2_000_000L; // Work must not be added to the full interval each iteration.
                long wait=p.finishAndWaitMillis(now);
                check(wait>=1&&wait<=34,"bounded positive wait");now+=wait*1_000_000L;
            }
            check(Math.abs(now-T-10_000_000_000L)<1_000_000L,"ten-second cadence error below one millisecond at "+hz);
            now+=5_000_000_000L;long wait=p.finishAndWaitMillis(now);
            check(wait==(1_000_000_000L/hz+999999)/1000000,"stall skips deadlines without catch-up");
            p.reset(now);check(p.finishAndWaitMillis(now)==wait,"pause reset bounded");
        }
    }
    private static void diagnostics() {
        EyeLookDiagnostics d=new EyeLookDiagnostics();DirectFaceMapper m=new DirectFaceMapper();OscFacePacket p=new OscFacePacket();
        check(d.sample(false,T,null,null,3)==null,"disabled never accesses data or formats values");
        m.accept(frame(T,7,true).putFloat(8+45*4,.8f).putFloat(8+11*4,.3f).putFloat(8+35*4,.5f).array(),T);
        p.encode(m.snapshot(T,3));String s=d.sample(true,T,m,p.bytes,3);
        check(s.contains("usedRaw[45,11,35,12,44,2,31,0]=0.8/0.3/0.5/0.0/0.0/0.0/0.0/0.0"),"raw direction slots labeled in actual order");
        check(s.contains("blob0to7=127/0/127/0/0/0/0/0"),"diagonal matches actual encoded bytes");
        check(s.contains("latestUsableL/R=1/1")&&s.contains("validAgeMsL/R=0/0"),"validity and ages");
        check(s.length()<2048&&!s.contains("JawOpen"),"bounded eye-only output");
        for(int i=1;i<500;i++)check(d.sample(true,T+i*1_000_000L,m,p.bytes,3)==null,"at most two samples/second");
        check(d.sample(false,T+500_000_000L,null,null,3)==null,"off at due boundary still silent");
        check(d.sample(true,T+500_000_000L,m,p.bytes,3)!=null,"half-second boundary");
        check(d.sample(true,T+500_000_001L,m,p.bytes,3)==null,"rapid reenable cannot bypass throttle");
        m.reset();p.encode(m.snapshot(T+1_000_000_000L,3));
        check(d.sample(true,T+1_000_000_000L,m,p.bytes,3).contains("latestUsableL/R=-1/-1"),"missing groups distinguish unknown");
    }
    private static void measuredPacing() throws InterruptedException {
        for(int hz:new int[]{60,90}) {
            long start=System.nanoTime(),now=start;DeadlinePacer p=new DeadlinePacer(hz,start);int count=0;
            while(now-start<3_000_000_000L) {
                // Measure host wait scheduling alone. Simulated work is covered deterministically above.
                Thread.sleep(p.finishAndWaitMillis(System.nanoTime()));count++;now=System.nanoTime();
            }
            System.out.printf(java.util.Locale.ROOT,"HOST pacing configured=%d measured=%.3fHz elapsed=%.3fs passes=%d (not headset)%n",hz,count/((now-start)/1e9),(now-start)/1e9,count);
        }
    }
    public static void main(String[] args)throws Exception {
        recovery();pacing();diagnostics();
        if(args.length>0&&args[0].equals("--measure"))measuredPacing();
        System.out.println("PASS: RecoveryPacingTest "+checks+" checks");
    }
}
