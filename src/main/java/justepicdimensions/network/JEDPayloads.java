package justepicdimensions.network;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** NeoForge main-thread payload work can outlive the listener which queued it. */
public final class JEDPayloads {
    private static final AttributeKey<Boolean> CLOSING = AttributeKey.valueOf("justepicdimensions:payload_closing");
    public static void markClosing(Channel channel) { channel.attr(CLOSING).set(true); }
    public static boolean closing(Channel channel) { return Boolean.TRUE.equals(channel.attr(CLOSING).get()); }
    public static boolean current(IPayloadContext context) {
        var connection = context.connection();
        return connection.isConnected() && !closing(connection.channel()) && connection.getPacketListener() == context.listener() && context.listener().isAcceptingMessages();
    }
    /** Record the native connection's reason as well as closing its socket. Never hop to the game thread. */
    public static void closeWithReason(Channel channel, Component reason) {
        markClosing(channel);
        JEDSyncWatchdog.execute(channel, () -> {
            var connection = channel.pipeline().get(net.minecraft.network.Connection.class);
            if (connection == null) channel.close();
            else connection.disconnect(reason);
        });
    }
    /** Play-phase server close without the native listener's executeBlocking hop to the game thread. */
    public static void disconnectPlay(Channel channel, Component reason) {
        markClosing(channel);
        JEDSyncWatchdog.execute(channel, () -> {
            try {
                if (channel.isActive() && channel.isWritable()) {
                    channel.writeAndFlush(new net.minecraft.network.protocol.common.ClientboundDisconnectPacket(reason)).addListener(ignored -> closeWithReason(channel, reason));
                    channel.eventLoop().schedule(() -> closeWithReason(channel, reason), 1, java.util.concurrent.TimeUnit.SECONDS);
                } else closeWithReason(channel, reason);
            } catch (RuntimeException error) { closeWithReason(channel, reason); }
        });
    }
    public static void disconnect(IPayloadContext context, Component reason) {
        var channel = context.connection().channel(); channel.attr(CLOSING).set(true);
        try { context.disconnect(reason); }
        catch (RuntimeException | Error error) { context.connection().disconnect(reason); throw error; }
        // A failed connection must not wait behind an outbound batch which cannot reach its socket.
        if (channel.isActive() && !channel.isWritable()) context.connection().disconnect(reason);
    }
    private JEDPayloads() {}
}
