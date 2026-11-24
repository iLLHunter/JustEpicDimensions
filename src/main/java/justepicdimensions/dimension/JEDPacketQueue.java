package justepicdimensions.dimension;

import io.netty.channel.ChannelPromise;
import java.util.*;

/** Event-loop-owned FIFO lanes. Registry replacements can pass held world traffic without scans. */
public final class JEDPacketQueue {
    public static final long MAX_BYTES=64L*1024*1024;
    public record Entry(Object message,ChannelPromise promise,long bytes,long order,long admittedAt,boolean registry,String kind){}
    private final int maxPackets;
    private final long maxBytes;
    private final ArrayDeque<Entry> ordinary=new ArrayDeque<>(),registries=new ArrayDeque<>();
    private final Map<String,Integer> kinds=new HashMap<>();
    private long bytes,sequence;
    public JEDPacketQueue(){this(JEDDimensionWriteGate.MAX_PENDING_PACKETS,MAX_BYTES);}
    public JEDPacketQueue(int maxPackets,long maxBytes){if(maxPackets<1||maxBytes<1)throw new IllegalArgumentException("Invalid packet queue budget");this.maxPackets=maxPackets;this.maxBytes=maxBytes;}
    public boolean add(Object message,ChannelPromise promise,long size,boolean registry){
        if(size<0)throw new IllegalArgumentException("Negative queued packet size");
        if(!registry&&(ordinary.size()>=maxPackets||size>maxBytes-bytes))return false;
        String kind=kind(message);var entry=new Entry(message,promise,size,sequence++,System.nanoTime(),registry,kind);
        if(registry)registries.addLast(entry);else{ordinary.addLast(entry);bytes+=size;kinds.merge(kind,1,Integer::sum);}return true;
    }
    public Entry peek(boolean awaiting){
        var batch=registries.peekFirst();if(awaiting)return batch;var packet=ordinary.peekFirst();
        return batch==null?packet:packet==null||batch.order()<packet.order()?batch:packet;
    }
    public void remove(Entry entry){
        var lane=entry.registry()?registries:ordinary;
        if(lane.peekFirst()!=entry)throw new IllegalStateException("Out-of-order registry queue removal");lane.removeFirst();
        if(!entry.registry()){bytes-=entry.bytes();kinds.compute(entry.kind(),(key,count)->count==1?null:count-1);}
    }
    public int packets(){return ordinary.size();}
    public boolean hasPacketCapacity(){return ordinary.size()<maxPackets&&bytes<maxBytes;}
    public long bytes(){return bytes;}
    public boolean isEmpty(){return ordinary.isEmpty()&&registries.isEmpty();}
    public String diagnostics(){
        var first=peek(false);long age=first==null?0:(System.nanoTime()-first.admittedAt())/1_000_000;
        var top=kinds.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(8).toList();
        return packets()+" packets, "+bytes+" accounted ordinary bytes, oldest="+age+"ms, types="+top;
    }
    private static String kind(Object message){
        if(message instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet)return packet.payload().type().id().toString();
        return message.getClass().getSimpleName();
    }
}
