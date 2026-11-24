package justepicdimensions.dimension;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.*;

/** Streams registry batches against native watermarks, preserving subsequent native packet order. */
public final class JEDDimensionWriteGate extends ChannelDuplexHandler {
    private static final int DRAIN_WRITES = 64, DRAIN_BYTES = 64 * 1024;
    private static final String NAME = "justepicdimensions:registry_writes";
    public static final int MAX_PENDING_PACKETS = 65536, MAX_PENDING_BATCHES = 2;
    public static final long MAX_SNAPSHOT_BYTES = 128L * 1024 * 1024;
    public record Batch(Iterator<?> packets, long retainedBytes) {
        public Batch { packets = new Cursor(Objects.requireNonNull(packets)); if (retainedBytes < 0 || retainedBytes > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Invalid retained snapshot size"); }
        public Batch(Iterator<?> packets) { this(packets, 0); }
    }
    /** Look ahead without losing a frame when this task's byte budget is exhausted. */
    private static final class Cursor implements Iterator<Object> {
        final Iterator<?> source; Object held; long bytes;
        Cursor(Iterator<?> source) { this.source=source; }
        public boolean hasNext() { return held!=null || source.hasNext(); }
        Object peek() { if(held==null)held=Objects.requireNonNull(source.next());return held; }
        public Object next() { var value=peek();held=null;bytes=0;return value; }
        void release() { if(held!=null){ReferenceCountUtil.release(held);held=null;} }
    }
    private static final io.netty.util.AttributeKey<Admission> ADMISSION = io.netty.util.AttributeKey.valueOf("justepicdimensions:registry_write_admission");
    private static final class Admission {
        private int batches; private long bytes;
        synchronized boolean reserve(long size) { if (batches >= MAX_PENDING_BATCHES || bytes + size > MAX_SNAPSHOT_BYTES) return false; batches++; bytes += size; return true; }
        synchronized void release(long size) { batches--; bytes -= size; }
    }
    private int queuedBatches;
    private long snapshotBytes;
    private final Set<ChannelPromise> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
    private final JEDPacketQueue pending = new JEDPacketQueue();
    private java.util.function.ToLongFunction<Object> sizer=JEDPacketSizer::simple;
    private boolean draining, scheduled;
    private String awaitingToken;
    /** Submit before registry callbacks can submit native writes, including when an old ACK races publication. */
    public static void preparing(Channel channel,net.minecraft.core.RegistryAccess registries){
        Runnable prepare=()->{
            if(!channel.isActive()||justepicdimensions.network.JEDPayloads.closing(channel))return;
            if(channel.pipeline().get(NAME)==null)channel.pipeline().addLast(NAME,new JEDDimensionWriteGate());
            var gate=(JEDDimensionWriteGate)channel.pipeline().get(NAME);gate.awaitingToken="preparing publication";
            if(registries!=null)gate.sizer=new JEDPacketSizer(registries,channel.pipeline());
        };
        if(channel.eventLoop().inEventLoop())prepare.run();else try{channel.eventLoop().execute(prepare);}catch(java.util.concurrent.RejectedExecutionException error){channel.close();}
    }
    public static ChannelFuture send(Channel channel, Batch batch) {
        return send(channel,batch,null);
    }
    /** Keep native packet objects before encoding/splitting until this registry is applied. */
    public static ChannelFuture send(Channel channel, Batch batch,String token) {
        return send(channel,batch,token,null);
    }
    static ChannelFuture send(Channel channel,Batch batch,String token,net.minecraft.core.RegistryAccess registries){
        var result = channel.newPromise();
        if (!channel.isActive() || justepicdimensions.network.JEDPayloads.closing(channel)) return result.setFailure(new ClosedChannelException());
        var admission = channel.attr(ADMISSION).get();
        if (admission == null) { var candidate = new Admission(); var previous = channel.attr(ADMISSION).setIfAbsent(candidate); admission = previous == null ? candidate : previous; }
        if (!admission.reserve(batch.retainedBytes())) {
            var error = new IllegalStateException("JED outgoing snapshot admission exceeded " + MAX_PENDING_BATCHES + " batches or " + MAX_SNAPSHOT_BYTES + " retained bytes");
            result.tryFailure(error);
            com.mojang.logging.LogUtils.getLogger().warn("{}; disconnecting only {}", error.getMessage(), channel.remoteAddress());
            justepicdimensions.network.JEDPayloads.disconnectPlay(channel,net.minecraft.network.chat.Component.literal(error.getMessage()));return result;
        }
        var reservation = admission;
        result.addListener(ignored -> reservation.release(batch.retainedBytes()));
        try { channel.eventLoop().execute(() -> {
            if (!channel.isActive()) { result.tryFailure(new ClosedChannelException()); return; }
            if (channel.pipeline().get(NAME) == null) channel.pipeline().addLast(NAME, new JEDDimensionWriteGate());
            if(registries!=null)((JEDDimensionWriteGate)channel.pipeline().get(NAME)).sizer=new JEDPacketSizer(registries,channel.pipeline());
            if(token!=null)((JEDDimensionWriteGate)channel.pipeline().get(NAME)).awaitingToken=token;
            channel.writeAndFlush(batch, result).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess() && channel.isActive() && !justepicdimensions.network.JEDPayloads.closing(channel)) channel.pipeline().fireExceptionCaught(future.cause());
            });
        }); } catch (java.util.concurrent.RejectedExecutionException error) { result.tryFailure(error); channel.close(); }
        return result;
    }
    public static void acknowledged(Channel channel,String token){
        Runnable done=()->{var gate=(JEDDimensionWriteGate)channel.pipeline().get(NAME);if(gate!=null&&Objects.equals(gate.awaitingToken,token)){gate.awaitingToken=null;gate.drain(channel.pipeline().context(gate));}};
        if(channel.eventLoop().inEventLoop())done.run();else try{channel.eventLoop().execute(done);}catch(java.util.concurrent.RejectedExecutionException error){channel.close();}
    }
    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        if (!context.channel().isActive() || justepicdimensions.network.JEDPayloads.closing(context.channel()) && !(message instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket)) { ReferenceCountUtil.release(message); promise.tryFailure(new ClosedChannelException()); return; }
        // Heartbeats have no registry dependency. A disconnect must also escape a stalled batch.
        if (message instanceof net.minecraft.network.protocol.common.ClientboundKeepAlivePacket || message instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket) { context.write(message, promise); context.flush(); return; }
        if (!(message instanceof Batch) && awaitingToken==null && pending.isEmpty() && context.channel().isWritable()) { context.write(message, promise); return; }
        if (!promise.setUncancellable()) { ReferenceCountUtil.release(message); return; }
        if(!(message instanceof Batch)&&!pending.hasPacketCapacity()){overflow(context,message,promise);return;}
        if (message instanceof Batch batch) {
            if (queuedBatches >= MAX_PENDING_BATCHES || snapshotBytes + batch.retainedBytes() > MAX_SNAPSHOT_BYTES) { overflow(context, message, promise); return; }
            queuedBatches++; snapshotBytes += batch.retainedBytes();
            promise.addListener(ignored -> { queuedBatches--; snapshotBytes -= batch.retainedBytes(); });
        }
        long size;
        try{size=message instanceof Batch?0:measure(message);}
        catch(Throwable error){ReferenceCountUtil.release(message);
            if(message instanceof net.minecraft.network.protocol.Packet<?> packet&&packet.isSkippable()&&!(error instanceof net.minecraft.network.SkipPacketException))error=new net.minecraft.network.SkipPacketException(error);
            promise.tryFailure(error);if(error instanceof net.minecraft.network.SkipPacketException)return;
            justepicdimensions.network.JEDPayloads.markClosing(context.channel());fail(error);context.pipeline().fireExceptionCaught(error);context.close();return;}
        if(!pending.add(message,promise,size,message instanceof Batch)){overflow(context,message,promise);return;}
        drain(context);
    }
    private long measure(Object message) {
        try{return sizer.applyAsLong(message);}
        catch(RuntimeException error){
            if(message instanceof net.minecraft.network.protocol.Packet<?> packet&&packet.isSkippable())throw new net.minecraft.network.SkipPacketException(error);
            if(error instanceof IndexOutOfBoundsException)return JEDPacketQueue.MAX_BYTES+1;
            // An addon may encode only in its live downstream handler. Preserve that handler's semantics.
            return JEDPacketSizer.simple(message);
        }
    }
    private void drain(ChannelHandlerContext context) {
        if (draining) return; draining = true; boolean wrote = false; int writes = 0; long bytes = 0;
        try {
            while (context.channel().isActive() && context.channel().isWritable() && !pending.isEmpty() && writes < DRAIN_WRITES) {
                var next = nextReady();if(next==null)break;
                if (next.message() instanceof Batch batch) {
                    if (!batch.packets().hasNext()) { remove(next); next.promise().trySuccess(); continue; }
                    var cursor=(Cursor)batch.packets();Object packet=cursor.peek();
                    if(cursor.bytes==0)cursor.bytes=measure(packet);
                    if(writes>0 && bytes+cursor.bytes>DRAIN_BYTES)break;
                    bytes+=cursor.bytes;cursor.next();
                    boolean last = !batch.packets().hasNext();
                    if (last) { remove(next); inFlight.add(next.promise()); }
                    if (last) next.promise().addListener(ignored -> inFlight.remove(next.promise()));
                    context.write(packet).addListener((ChannelFutureListener) future -> {
                        if (!future.isSuccess()) next.promise().tryFailure(future.cause());
                        else if (last) next.promise().trySuccess();
                    });
                } else { if(writes>0 && bytes+next.bytes()>DRAIN_BYTES)break;bytes+=next.bytes();remove(next); context.write(next.message(), next.promise()); }
                wrote = true;writes++;
                if(bytes>=DRAIN_BYTES)break; // One indivisible native packet may itself exceed the task budget.
            }
            if (wrote) context.flush();
        } catch (Throwable error) {
            fail(error);
            // This gate is after Minecraft's packet handler. Start at the pipeline head so its
            // native connection error handler receives the failure instead of the unhandled tail.
            if (context.channel().isActive()) context.pipeline().fireExceptionCaught(error);
            context.close();
        } finally {
            draining = false;
            // Yield even when compression/fast writes keep the socket writable, and avoid recursion from flush callbacks.
            if (nextReady()!=null && context.channel().isActive() && context.channel().isWritable()) schedule(context);
        }
    }
    private void schedule(ChannelHandlerContext context) {
        if (scheduled || draining) return; scheduled = true;
        try { context.executor().execute(() -> { scheduled = false; drain(context); }); }
        catch (java.util.concurrent.RejectedExecutionException error) { scheduled = false; fail(error); context.close(); }
    }
    @Override public void channelWritabilityChanged(ChannelHandlerContext context) {
        if (context.channel().isWritable() && nextReady()!=null) schedule(context);
        context.fireChannelWritabilityChanged();
    }
    @Override public void channelInactive(ChannelHandlerContext context) throws Exception { fail(new ClosedChannelException()); super.channelInactive(context); }
    @Override public void handlerRemoved(ChannelHandlerContext context) { fail(new ClosedChannelException()); }
    private void fail(Throwable error) {
        awaitingToken=null;
        while (!pending.isEmpty()) { var next = removeFirst(); if(next.message() instanceof Batch batch)((Cursor)batch.packets()).release();ReferenceCountUtil.release(next.message()); next.promise().tryFailure(error); }
        for (var promise : List.copyOf(inFlight)) promise.tryFailure(error); inFlight.clear();
    }
    private JEDPacketQueue.Entry removeFirst() {
        var next = pending.peek(false);remove(next);return next;
    }
    private void remove(JEDPacketQueue.Entry next){
        pending.remove(next);
    }
    private JEDPacketQueue.Entry nextReady(){
        // A replacement registry must not wait behind world traffic held for its predecessor.
        // Both registry batches and ordinary native packets retain their own FIFO order.
        return pending.peek(awaitingToken!=null);
    }
    private void overflow(ChannelHandlerContext context, Object message, ChannelPromise promise) {
        var error = new IllegalStateException("JED outgoing registry backlog exceeded: " + pending.diagnostics() + ", " + queuedBatches + " snapshots, " + snapshotBytes + " retained snapshot bytes; awaiting ACK="+(awaitingToken!=null)+", writable="+context.channel().isWritable());
        justepicdimensions.network.JEDPayloads.markClosing(context.channel());
        ReferenceCountUtil.release(message); promise.tryFailure(error); fail(error);
        com.mojang.logging.LogUtils.getLogger().warn("{}; disconnecting only {}", error.getMessage(), context.channel().remoteAddress());
        var reason=net.minecraft.network.chat.Component.literal(error.getMessage());
        // Start before this gate so a saturated queue cannot swallow its own disconnect packet.
        try {
            context.writeAndFlush(new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(reason)).addListener(ignored -> justepicdimensions.network.JEDPayloads.closeWithReason(context.channel(),reason));
            context.executor().schedule(()->justepicdimensions.network.JEDPayloads.closeWithReason(context.channel(),reason),1,java.util.concurrent.TimeUnit.SECONDS);
        } catch(RuntimeException failure) { justepicdimensions.network.JEDPayloads.closeWithReason(context.channel(),reason); }
    }
    public static String backlog(Channel channel) {
        var gate = (JEDDimensionWriteGate) channel.pipeline().get(NAME);
        return gate == null ? "no queued registry writes" : gate.pending.diagnostics() + ", " + gate.queuedBatches + " snapshots, " + gate.snapshotBytes + " retained snapshot bytes"+(gate.awaitingToken==null?"":"; awaiting client registry application");
    }
    private JEDDimensionWriteGate() {}
}
