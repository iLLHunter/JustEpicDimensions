package justepicdimensions.network;

import io.netty.channel.*;
import justepicdimensions.dimension.JEDDimensionReadGate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One connection's decoded JED work budget. Permissions and gameplay remain on the main thread. */
public final class JEDPayloadWork extends ChannelInboundHandlerAdapter {
    public static final int MAX_QUEUED_BYTES = 1024 * 1024;
    private static final String NAME = "justepicdimensions:payload_work";
    private int bytes;
    private boolean closed;
    private Runnable release;
    public static void enqueue(IPayloadContext context, int payloadBytes, Runnable work) {
        var channel = context.connection().channel();
        // In-process callers can already be on the game thread. Actual decoded traffic uses the event loop.
        if (!channel.eventLoop().inEventLoop()) { context.enqueueWork(() -> apply(context, work)); return; }
        if (!channel.isActive()) return;
        var owner = (JEDPayloadWork) channel.pipeline().get(NAME);
        if (owner == null) { owner = new JEDPayloadWork(); channel.pipeline().addLast(NAME, owner); }
        var state = owner; int cost = Math.addExact(Math.max(1, payloadBytes), 512);
        state.bytes = Math.addExact(state.bytes, cost);
        if (state.bytes >= MAX_QUEUED_BYTES && state.release == null) state.release = JEDDimensionReadGate.pause(channel);
        try {
            context.enqueueWork(() -> apply(context, work))
                .whenComplete((ignored, error) -> complete(channel, state, cost));
        } catch (RuntimeException | Error error) { complete(channel, state, cost); throw error; }
    }
    private static void apply(IPayloadContext context, Runnable work) {
        if (!JEDPayloads.current(context)) return;
        try { work.run(); }
        catch (Throwable error) {
            com.mojang.logging.LogUtils.getLogger().error("JED payload application failed", error);
            justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.sync", justepicdimensions.JEDText.error(error)));
        }
    }
    private static void complete(Channel channel, JEDPayloadWork state, int cost) {
        Runnable done = () -> {
            if (state.closed) return;
            state.bytes -= cost;
            if (state.bytes < MAX_QUEUED_BYTES && state.release != null) {
                var release = state.release; state.release = null; release.run();
            }
        };
        if (channel.eventLoop().inEventLoop()) done.run();
        else try { channel.eventLoop().execute(done); }
        catch (java.util.concurrent.RejectedExecutionException error) { channel.close(); }
    }
    @Override public void channelInactive(ChannelHandlerContext context) throws Exception { clear(); super.channelInactive(context); }
    @Override public void handlerRemoved(ChannelHandlerContext context) { clear(); }
    private void clear() {
        closed = true; bytes = 0;
        if (release != null) { var held = release; release = null; held.run(); }
    }
    private JEDPayloadWork() {}
}
