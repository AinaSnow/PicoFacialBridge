package dev.pico.facialprobe;

import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** No Android, headset, network permissions or third-party test dependencies required. */
public final class DirectOscTest {
    private static int checks;
    private static final long T = 10_000_000_000L;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void eq(float actual, float expected, String label) {
        check(Math.abs(actual - expected) < .00001f, label + ": " + actual + " != " + expected);
    }
    private static ByteBuffer frame(int version, long time, int updates) {
        ByteBuffer f = ByteBuffer.allocate(384).order(ByteOrder.LITTLE_ENDIAN);
        f.putLong(0,time).putFloat(296,1).putFloat(300,1);
        if (version != 0) f.putInt(228,0x31495045).putInt(232,version).putInt(220,1).putInt(224,1)
                .putInt(236,updates).putLong(240,time).putLong(248,time);
        return f;
    }
    private static void raw(ByteBuffer frame, int slot, float value) { frame.putFloat(8 + 4*slot,value); }
    private static float[] mapped(ByteBuffer frame) {
        DirectFaceMapper m = new DirectFaceMapper(); m.accept(frame.array(),T); return m.snapshot(T,3);
    }
    private static void mappings() {
        // Golden expected wire destinations for every PICO slot (independent of implementation table).
        int[][] expected = {
            {7},{63},{5},{22,23},{18,20},{43},{64},{36},{58,60},{80},{50,51,52,53},
            {1},{3},{62},{44,45},{37},{19,21},{81},{46,47},{71,73},{83},{70,72},
            {82},{78},{67,69},{39},{15},{75},{9},{31},{25},{6},{38},{77},
            {54,55,56,57},{2},{24},{30},{8},{59,61},{74},{14},{76},{32,33},{4},{0},
            {16},{17},{66,68},{79},{65},{86}
        };
        for (int slot=0;slot<52;slot++) {
            ByteBuffer f=frame(0,T,7); raw(f,slot,.25f); float[] out=mapped(f);
            for(int key=0;key<102;key++) {
                float want=0;
                for(int k:expected[slot]) if(k==key) want=(slot==46||slot==47)? .5f : .25f;
                eq(out[key],want,"source "+slot+" wire "+key);
            }
        }
        ByteBuffer f=frame(0,T,7);
        raw(f,45,.8f); raw(f,11,.3f); raw(f,44,.1f); raw(f,2,.7f);
        raw(f,35,.2f); raw(f,12,.9f); raw(f,31,.9f); raw(f,0,.4f);
        float[] o=mapped(f);
        eq(o[0],.5f,"right out-in");eq(o[1],0,"right reverse zero");
        eq(o[4],0,"left out zero");eq(o[5],.6f,"left in-out");
        eq(o[3],.7f,"right down-up");eq(o[6],.5f,"left up-down");
        raw(f,7,Float.NaN); raw(f,38,Float.POSITIVE_INFINITY);raw(f,28,Float.NEGATIVE_INFINITY);
        raw(f,21,-3);raw(f,19,3);raw(f,46,.8f);o=mapped(f);
        eq(o[36],0,"NaN");eq(o[8],0,"+Inf");eq(o[9],0,"-Inf");
        eq(o[70],0,"negative clamp");eq(o[71],1,"upper clamp");eq(o[16],1,"gain clamp");
    }
    private static void cache() {
        DirectFaceMapper m=new DirectFaceMapper();
        ByteBuffer f=frame(2,T,7);raw(f,7,.7f);raw(f,28,.2f);raw(f,38,.3f);raw(f,3,.1f);f.putFloat(216,.8f);
        m.accept(f.array(),T);float[] o=m.snapshot(T,3);
        eq(o[22],.8f,"enhanced RightInnerUp");eq(o[23],.1f,"enhanced left brow");
        ByteBuffer left=frame(2,T+1,1);raw(left,28,.9f);
        m.accept(left.array(),T+1);o=m.snapshot(T+1,3);
        eq(o[9],.9f,"left advanced");eq(o[8],.3f,"right retained");eq(o[36],.7f,"mouth retained");
        ByteBuffer right=frame(2,T,2).putLong(248,T+2);raw(right,38,.6f);right.putFloat(216,.4f);
        m.accept(right.array(),T+2);o=m.snapshot(T+2,3);
        eq(o[8],.6f,"right independent clock");eq(o[9],.9f,"left retained after right");eq(o[36],.7f,"mouth after right");
        ByteBuffer mouth=frame(2,T,4).putLong(240,T+3);raw(mouth,7,.4f);
        m.accept(mouth.array(),T+3);o=m.snapshot(T+3,3);eq(o[36],.4f,"mouth independent clock");eq(o[8],.6f,"right after mouth");
        raw(mouth,7,1);m.accept(mouth.array(),T+4);eq(m.snapshot(T+4,3)[36],.4f,"duplicate timestamp ignored");
        m.accept(null,T+5);m.accept(new byte[20],T+5);eq(m.snapshot(T+5,3)[36],.4f,"missing frame retained");
        ByteBuffer invalid=frame(2,T+6,7).putInt(220,0).putInt(224,0).putFloat(300,0);
        m.accept(invalid.array(),T+6);o=m.snapshot(T+6,3);eq(o[9],.9f,"invalid left retained");eq(o[36],0,"explicitly invalid mouth neutral immediately");
        m.accept(frame(2,T+100_000_000L,1).array(),T+100_000_000L);
        o=m.snapshot(T+DirectFaceMapper.STALE_NS+4,3);
        eq(o[36],0,"mouth expired independently");eq(o[8],0,"right expired independently");
        check(m.eyeFresh(T+DirectFaceMapper.STALE_NS+4),"left still fresh");
        for(float v:m.snapshot(T+DirectFaceMapper.STALE_NS+100_000_001L,3)) eq(v,0,"all stale neutral");
        m.reset();m.accept(f.array(),T);o=m.snapshot(T,2);for(int k=0;k<30;k++) eq(o[k],0,"eye disabled");eq(o[36],.7f,"mouth enabled");
        m.reset();m.accept(f.array(),T);o=m.snapshot(T,1);for(int k=30;k<102;k++) eq(o[k],0,"mouth disabled");
        m.reset();for(float v:m.snapshot(T,3))eq(v,0,"reset");
        m.accept(frame(0,T-DirectFaceMapper.STALE_NS,7).array(),T);check(!m.eyeFresh(T),"old timestamp rejected");
        m.accept(frame(0,T+101_000_000L,7).array(),T);check(!m.eyeFresh(T),"future timestamp rejected");
        m.accept(f.array(),T);ByteBuffer normal=frame(0,T+1,7);raw(normal,3,.5f);m.accept(normal.array(),T+1);
        o=m.snapshot(T+1,3);eq(o[22],.5f,"normal mode reset right brow");eq(o[36],0,"mode change clears mouth");
        m.reset();ByteBuffer v1=frame(1,T,7);raw(v1,7,.2f);v1.putFloat(216,.7f);m.accept(v1.array(),T);
        eq(m.snapshot(T,3)[22],.7f,"v1 right brow");eq(m.snapshot(T,3)[36],.2f,"v1 mouth");
    }
    private static void packet() throws Exception {
        OscFacePacket p=new OscFacePacket();float[] values=new float[102];
        values[36]=1;values[8]=.5f;values[9]=2;values[10]=Float.NaN;values[11]=Float.POSITIVE_INFINITY;values[12]=-1;
        p.encode(values);
        check(p.bytes.length==132,"OSC length");
        check(new String(p.bytes,0,18,StandardCharsets.US_ASCII).equals("/tracking/face/v1\0"),"OSC address");
        for(int i=18;i<20;i++)check(p.bytes[i]==0,"address padding");
        check(p.bytes[20]==','&&p.bytes[21]=='b'&&p.bytes[22]==0&&p.bytes[23]==0,"blob tag padding");
        check(ByteBuffer.wrap(p.bytes).order(ByteOrder.BIG_ENDIAN).getInt(24)==102,"big-endian blob length");
        check((p.bytes[64]&255)==255,"jaw byte offset");check((p.bytes[36]&255)==127,"truncate .5");
        check((p.bytes[37]&255)==255,"clamp one");
        for(int i=38;i<=40;i++)check(p.bytes[i]==0,"non-finite/negative zero");
        check(p.bytes[130]==0&&p.bytes[131]==0,"blob padding");
        byte[] original=p.bytes;
        // Actual local UDP receiver independently decodes OSC framing, not a byte-array-only test.
        try(DatagramSocket rx=new DatagramSocket(0,InetAddress.getLoopbackAddress());DatagramSocket tx=new DatagramSocket()) {
            rx.setSoTimeout(2000);tx.send(new DatagramPacket(p.bytes,p.bytes.length,rx.getLocalAddress(),rx.getLocalPort()));
            DatagramPacket received=new DatagramPacket(new byte[512],512);rx.receive(received);
            check(received.getLength()==132,"UDP datagram size");
            ByteBuffer decoded=ByteBuffer.wrap(received.getData(),0,received.getLength()).order(ByteOrder.BIG_ENDIAN);
            check(decoded.getInt(24)==102,"UDP OSC blob length");check((decoded.get(28+36)&255)==255,"UDP JawOpen");
            check(Arrays.equals(p.bytes,Arrays.copyOf(received.getData(),received.getLength())),"UDP entire message");
        }
        Arrays.fill(values,0);p.encode(values);check(p.bytes==original,"fixed packet reused");
        for(int i=28;i<132;i++)check(p.bytes[i]==0,"no old blob bytes");
        values[36]=1;p.encode(values);p.neutral();check(p.bytes[64]==0&&p.bytes[27]==102,"neutral retains framing");
        try{p.encode(new float[101]);throw new AssertionError("short output accepted");}catch(IllegalArgumentException expected){checks++;}
    }
    private static void destination() {
        OscDestination.validate("192.168.1.12",9000,60);checks++;
        for(String ip:new String[]{"", "localhost","127.0.0.1","0.0.0.0","255.255.255.255","239.1.1.1","192.168.1.999","1.2.3","1..2.3"})
            try{OscDestination.validate(ip,9000,60);throw new AssertionError("accepted "+ip);}catch(IllegalArgumentException expected){checks++;}
        for(int[] bad:new int[][]{{0,60},{65536,60},{9000,0},{9000,121}})
            try{OscDestination.validate("192.168.1.12",bad[0],bad[1]);throw new AssertionError("accepted invalid port/rate");}catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) throws Exception {
        mappings();cache();packet();destination();System.out.println("PASS: DirectOscTest " + checks + " checks, including local UDP decode");
    }
}
