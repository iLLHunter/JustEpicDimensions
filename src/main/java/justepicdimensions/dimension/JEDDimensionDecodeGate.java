package justepicdimensions.dimension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import justepicdimensions.network.JEDPayloads;
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;

/** Holds framed, decompressed world bytes without hiding liveness traffic behind registry application. */
public final class JEDDimensionDecodeGate extends ChannelInboundHandlerAdapter {
    private static final String NAME = "justepicdimensions:registry_decode";
    private static final int MAX_BYTES = 64 * 1024 * 1024, MAX_PACKETS = 65536, DRAIN_PACKETS = 2;
    private final Connection connection;
    private final ProtocolInfo<?> protocol;
    private final int keepAliveId, disconnectId;
    private final ArrayDeque<ByteBuf> pending = new ArrayDeque<>();
    private ChannelHandlerContext context;
    private long bytes;
    private int holds;
    private boolean draining, scheduled, closed, receiving;
    private JEDDimensionDecodeGate(Connection connection) {
        this.connection = connection; protocol = connection.getInboundProtocol();
        keepAliveId = packetId(new ClientboundKeepAlivePacket(0));
        disconnectId = packetId(new ClientboundDisconnectPacket(net.minecraft.network.chat.Component.empty()));
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private int packetId(Packet<?> packet) {
        var buffer = Unpooled.buffer();
        try { ((net.minecraft.network.codec.StreamCodec) protocol.codec()).encode(buffer, packet); return VarInt.read(buffer); }
        finally { buffer.release(); }
    }
    private static JEDDimensionDecodeGate gate(Connection connection) {
        var channel = connection.channel();
        if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Registry decode barrier requires the event loop");
        var gate = (JEDDimensionDecodeGate) channel.pipeline().get(NAME);
        if (gate != null && gate.protocol != connection.getInboundProtocol()) {
            if (gate.holds != 0 || !gate.pending.isEmpty() || gate.receiving) throw new IllegalStateException("Registry barrier crossed a native protocol change");
            channel.pipeline().remove(gate); gate = null;
        }
        if (gate == null) {
            if (channel.pipeline().context("decoder") == null) throw new IllegalStateException("Native packet decoder is missing");
            gate = new JEDDimensionDecodeGate(connection); channel.pipeline().addBefore("decoder", NAME, gate);
        }
        return gate;
    }
    /** Start liveness handling with the first frame, before the final publication barrier is held. */
    public static void receiving(Connection connection) { gate(connection).receiving = true; }
    public static Runnable pause(Connection connection) {
        var channel = connection.channel(); var gate = gate(connection);
        gate.holds++; var owner = gate;
        return new Runnable() {
            private boolean released;
            public void run() {
                if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Registry decode release requires the event loop");
                if (released) return; released = true;
                if (--owner.holds == 0) { owner.receiving = false; if (!owner.closed && channel.isActive() && !JEDPayloads.closing(channel)) owner.drain(); }
            }
        };
    }
    @Override public void handlerAdded(ChannelHandlerContext context) { this.context = context; }
    @Override public void channelRead(ChannelHandlerContext context, Object message) {
        if (closed || JEDPayloads.closing(context.channel())) { ReferenceCountUtil.release(message); return; }
        if (!receiving && holds == 0 && pending.isEmpty()) { context.fireChannelRead(message); return; }
        if (!(message instanceof ByteBuf buffer)) { ReferenceCountUtil.release(message); fail("Unexpected unframed packet at the registry decode barrier"); return; }
        boolean queued = false;
        try {
            // Decode only these two context-free native types; every world-dependent byte stays untouched.
            int id = VarInt.read(buffer.duplicate());
            if (id == keepAliveId || id == disconnectId) {
                if (connection.getInboundProtocol() != protocol) throw new IllegalStateException("Native protocol changed during registry synchronization");
                var packet = protocol.codec().decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("Trailing bytes in synchronization control packet");
                if (packet instanceof ClientboundKeepAlivePacket keep) connection.send(new ServerboundKeepAlivePacket(keep.getId()));
                else if (packet instanceof ClientboundDisconnectPacket stop) connection.disconnect(stop.reason());
                else throw new IllegalStateException("Unexpected synchronization control packet");
                return;
            }
            if (holds == 0 && pending.isEmpty()) { queued = true; context.fireChannelRead(buffer); return; }
            long size = buffer.readableBytes();
            if (pending.size() >= MAX_PACKETS || bytes + size > MAX_BYTES) {
                fail("Client registry backlog exceeded " + MAX_PACKETS + " packets or " + MAX_BYTES + " bytes"); return;
            }
            // A retained slice can pin a much larger parent allocation. Own exactly the bounded bytes.
            pending.addLast(buffer.copy()); bytes += size;
        } catch (Throwable error) {
            fail("Invalid synchronization control packet: " + error.getMessage());
        } finally { if (!queued) buffer.release(); }
    }
    private void drain() {
        if (draining || closed) return; draining = true;
        try {
            for (int i = 0; i < DRAIN_PACKETS && holds == 0 && !pending.isEmpty() && context.channel().isActive() && !JEDPayloads.closing(context.channel()); i++) {
                var buffer = pending.removeFirst(); bytes -= buffer.readableBytes(); context.fireChannelRead(buffer);
            }
            context.fireChannelReadComplete();
        } finally {
            draining = false;
            if (holds == 0 && !pending.isEmpty() && !closed && context.channel().isActive() && !scheduled) {
                scheduled = true;
                try { context.executor().execute(() -> { scheduled = false; drain(); }); }
                catch (java.util.concurrent.RejectedExecutionException error) { clear(); context.close(); }
            }
        }
    }
    private void fail(String message) {
        com.mojang.logging.LogUtils.getLogger().warn("JED closed a client registry barrier: {}", message);
        clear(); JEDPayloads.markClosing(context.channel());
        connection.disconnect(justepicdimensions.JEDText.tr("connection.sync", net.minecraft.network.chat.Component.literal(message)));
    }
    private void clear() { closed = true; ByteBuf buffer; while ((buffer = pending.pollFirst()) != null) buffer.release(); bytes = 0; }
    @Override public void channelInactive(ChannelHandlerContext context) throws Exception { clear(); super.channelInactive(context); }
    @Override public void handlerRemoved(ChannelHandlerContext context) { clear(); }
}
