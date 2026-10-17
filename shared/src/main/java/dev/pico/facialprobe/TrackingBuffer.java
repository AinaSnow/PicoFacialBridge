package dev.pico.facialprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounds-checked reader for the PICO shared-memory ring (version 1). */
public final class TrackingBuffer {
    private TrackingBuffer() {}

    public static byte[] latest(ByteBuffer source, int minimumSlotBytes) {
        return latest(source, minimumSlotBytes, 0);
    }

    public static byte[] latest(ByteBuffer source, int minimumSlotBytes, int timestampOffset) {
        Header h = header(source, minimumSlotBytes, timestampOffset);
        ByteBuffer data = h.data;
        if (h.index == -1) return null;
        byte[] sample = new byte[h.stride];
        int start = h.offset + h.stride * h.index;
        long timestamp = data.getLong(start + timestampOffset);
        data.position(start); data.get(sample);
        if (data.getInt(12) != h.index || data.getLong(start + timestampOffset) != timestamp) return null;
        return sample;
    }

    private static Header header(ByteBuffer source, int minimumSlotBytes, int timestampOffset) {
        ByteBuffer data = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (data.capacity() < 20) throw new IllegalArgumentException("Short ring header");
        int version = data.getInt(0);
        long stride = Integer.toUnsignedLong(data.getInt(4));
        long capacity = Integer.toUnsignedLong(data.getInt(8));
        int index = data.getInt(12);
        long offset = Integer.toUnsignedLong(data.getInt(16));
        if (version != 1 || stride < minimumSlotBytes || stride > data.capacity() || timestampOffset < 0 || timestampOffset + 8L > stride
                || capacity == 0 || capacity > data.capacity() / stride || offset < 20
                || offset + stride * capacity > data.capacity()
                || index < -1 || index >= capacity) {
            throw new IllegalArgumentException("Invalid ring version=" + version + " stride=" + stride
                    + " capacity=" + capacity + " index=" + index + " offset=" + offset
                    + " mmapSize=" + data.capacity());
        }
        return new Header(data, (int)stride, (int)capacity, index, (int)offset);
    }

    private static final class Header {
        final ByteBuffer data;
        final int stride, capacity, index, offset;
        Header(ByteBuffer data, int stride, int capacity, int index, int offset) {
            this.data=data; this.stride=stride; this.capacity=capacity; this.index=index; this.offset=offset;
        }
    }

    /** Drains retained, published samples after a scheduling delay instead of reading only the latest slot. */
    public static final class Cursor {
        private final ByteBuffer memory;
        private final int minimum, timestampOffset;
        private final Runnable afterHeader, afterCopy;
        private long lastTimestamp, lastRight, lastMouth, overrunCount;
        public Cursor(ByteBuffer memory, int minimum, int timestampOffset) {
            this(memory,minimum,timestampOffset,null,null);
        }
        // Package-private controlled scheduling seam for publication-race regression tests.
        Cursor(ByteBuffer memory,int minimum,int timestampOffset,Runnable afterHeader,Runnable afterCopy) {
            this.memory=memory; this.minimum=minimum; this.timestampOffset=timestampOffset;
            this.afterHeader=afterHeader;this.afterCopy=afterCopy;
            if (header(memory,minimum,timestampOffset).capacity < 2) throw new IllegalArgumentException("History requires two slots");
        }
        public long overruns() { return overrunCount; }
        public List<byte[]> read() {
            Header h=header(memory,minimum,timestampOffset);
            if(afterHeader!=null)afterHeader.run();
            if(h.index<0) return Collections.emptyList();
            if(grouped(h,h.index)) return readGrouped(h);
            long newest=h.data.getLong(h.offset+h.stride*h.index+timestampOffset);
            if(newest<=lastTimestamp) return Collections.emptyList();
            if(lastTimestamp==0) {
                byte[] sample=copy(h,h.index,newest);
                if(sample==null) return Collections.emptyList();
                lastTimestamp=newest;
                return Collections.singletonList(sample);
            }
            List<byte[]> reverse=new ArrayList<>();
            boolean foundPrevious=false;
            for(int back=0;back<h.capacity-1;back++) {
                int slot=(h.index-back+h.capacity)%h.capacity;
                long timestamp=h.data.getLong(h.offset+h.stride*slot+timestampOffset);
                if(timestamp<=lastTimestamp){foundPrevious=true;break;}
                byte[] sample=copy(h,slot,newest);
                if(sample==null) return Collections.emptyList(); // Retry without advancing the cursor.
                reverse.add(sample);
            }
            int reserved=(h.index+1)%h.capacity; // Writer may already be modifying this slot: never copy its payload.
            if(!foundPrevious && h.data.getLong(h.offset+h.stride*reserved+timestampOffset)==lastTimestamp) foundPrevious=true;
            if(!foundPrevious) overrunCount++;
            Collections.reverse(reverse);
            lastTimestamp=newest;
            return reverse;
        }
        private List<byte[]> readGrouped(Header h) {
            List<byte[]> retained=new ArrayList<>();
            boolean foundPrevious=false;
            // A held snapshot is not a history boundary. Scan all published slots, oldest first.
            for(int back=h.capacity-2;back>=0;back--) {
                int slot=(h.index-back+h.capacity)%h.capacity;
                if(!grouped(h,slot)) continue;
                long left=h.data.getLong(h.offset+h.stride*slot+timestampOffset);
                long right=sideTimestamp(h,slot,248), mouth=sideTimestamp(h,slot,240);
                if(left==0 && right==0 && mouth==0) continue;
                byte[] sample=copy(h,slot,0);
                if(sample==null) return Collections.emptyList();
                retained.add(sample);
                if(left<=lastTimestamp && right<=lastRight && mouth<=lastMouth) foundPrevious=true;
            }
            if(h.data.getInt(12)!=h.index) return Collections.emptyList();
            long left=lastTimestamp, right=lastRight, mouth=lastMouth;
            List<byte[]> updates=new ArrayList<>();
            for(byte[] sample:retained) {
                ByteBuffer src=ByteBuffer.wrap(sample).order(ByteOrder.LITTLE_ENDIAN);
                int pending=src.getInt(236), advanced=0;
                if((pending&1)!=0 && src.getLong(0)>left) { left=src.getLong(0); advanced|=1; }
                if((pending&2)!=0 && src.getLong(248)>right) { right=src.getLong(248); advanced|=2; }
                if((pending&4)!=0 && src.getLong(240)>mouth) { mouth=src.getLong(240); advanced|=4; }
                if(advanced!=0) {
                    src.putInt(236,advanced);
                    updates.add(sample);
                }
            }
            if(!updates.isEmpty() && (lastTimestamp!=0 || lastRight!=0 || lastMouth!=0) && !foundPrevious) {
                int reserved=(h.index+1)%h.capacity;
                if(h.data.getLong(h.offset+h.stride*reserved+timestampOffset)!=lastTimestamp ||
                        sideTimestamp(h,reserved,248)!=lastRight || sideTimestamp(h,reserved,240)!=lastMouth) overrunCount++;
            }
            lastTimestamp=left;lastRight=right;lastMouth=mouth;
            return updates;
        }
        private boolean grouped(Header h,int slot) {
            int start=h.offset+h.stride*slot;
            return minimum>=384 && h.data.getInt(start+228)==0x31495045 && h.data.getInt(start+232)==2;
        }
        private long sideTimestamp(Header h,int slot,int offset) {
            int start=h.offset+h.stride*slot;
            return grouped(h,slot) ? h.data.getLong(start+offset) : 0;
        }
        private byte[] copy(Header h,int slot,long newest) {
            int start=h.offset+h.stride*slot;
            int publicationBefore=h.data.getInt(12);
            if(slot==(publicationBefore+1)%h.capacity)return null;
            long before=h.data.getLong(start+timestampOffset);
            long right=sideTimestamp(h,slot,248),mouth=sideTimestamp(h,slot,240);
            byte[] sample=new byte[h.stride];h.data.position(start);h.data.get(sample);
            if(afterCopy!=null)afterCopy.run();
            int published=h.data.getInt(12);
            if(publicationBefore!=published || before<0 || before==0 && right==0 && mouth==0 ||
                    !grouped(h,slot) && before>newest || h.data.getLong(start+timestampOffset)!=before
                    || sideTimestamp(h,slot,248)!=right || sideTimestamp(h,slot,240)!=mouth
                    || slot==(published+1)%h.capacity) return null;
            return sample;
        }
    }
}
