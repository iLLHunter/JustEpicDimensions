package justepicdimensions.dimension;

import io.netty.channel.*;
import io.netty.handler.flow.FlowControlHandler;
import justepicdimensions.definition.JEDDefinitionException;

/** Keeps explicit decoder read requests behind the same registry-publication barrier as automatic reads. */
public final class JEDDimensionReadGate extends ChannelOutboundHandlerAdapter {
    private static final String NAME = "justepicdimensions:registry_reads";
    private ChannelHandlerContext context;
    private int holds;
    private boolean restoreAutoRead, requested;
    @Override public void handlerAdded(ChannelHandlerContext context) { this.context = context; }
    @Override public void read(ChannelHandlerContext context) {
        if (holds == 0) context.read(); else requested = true;
    }
    /** Must run in the network handler, before decoding any subsequent native packets. */
    public static Runnable pause(Channel channel) {
        if (!channel.eventLoop().inEventLoop()) throw new JEDDefinitionException("Dimension decode barrier must start on the network event loop");
        var gate = (JEDDimensionReadGate) channel.pipeline().get(NAME);
        if (gate == null) {
            var flow = framedFlow(channel.pipeline());
            if (flow == null) throw new JEDDefinitionException("Native framed inbound flow control is missing or ambiguous; registry decoding cannot be paused safely");
            gate = new JEDDimensionReadGate(); channel.pipeline().addAfter(flow.name(), NAME, gate);
        }
        if (gate.holds++ == 0) {
            gate.requested = false; gate.restoreAutoRead = channel.config().isAutoRead(); channel.config().setAutoRead(false);
        }
        var owner = gate;
        return new Runnable() {
            private boolean released;
            @Override public void run() {
                if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Dimension read barrier release requires the event loop");
                if (released) return; released = true;
                if (--owner.holds != 0 || !channel.isActive() || justepicdimensions.network.JEDPayloads.closing(channel)) return;
                if (owner.restoreAutoRead) {
                    // setAutoRead(true) already requests a read. A second explicit read can cross a newer barrier.
                    if (channel.config().isAutoRead()) owner.context.read(); else channel.config().setAutoRead(true);
                } else if (owner.requested) owner.context.read();
            }
        };
    }
    private static ChannelHandlerContext framedFlow(ChannelPipeline pipeline) {
        var decoder = pipeline.context("decoder");
        ChannelHandlerContext selected = null;
        if (decoder != null) {
            boolean framed = pipeline.context("splitter") == null;
            for (var entry : pipeline) {
                if (entry.getKey().equals("splitter")) { framed = true; selected = null; continue; }
                if (entry.getKey().equals(decoder.name())) break;
                if (framed && entry.getValue() instanceof FlowControlHandler) selected = pipeline.context(entry.getKey());
            }
            return selected;
        }
        // Unconfigured/local test pipelines can omit the named decoder. Only an unambiguous
        // flow handler is safe there; selecting the first of several can expose a later manual read.
        for (var entry : pipeline) if (entry.getValue() instanceof FlowControlHandler) {
            if (selected != null) return null;
            selected = pipeline.context(entry.getKey());
        }
        return selected;
    }
    private JEDDimensionReadGate() {}
}
