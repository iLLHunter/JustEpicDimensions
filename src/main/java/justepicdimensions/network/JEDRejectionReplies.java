package justepicdimensions.network;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.minecraft.server.level.ServerPlayer;

/** Shared rejection budget, owned by the connection and reclaimed with it. */
public final class JEDRejectionReplies {
    public static final int PER_SECOND = 4;
    private static final AttributeKey<Window> KEY = AttributeKey.valueOf("justepicdimensions:rejection_replies");
    private static final class Window { long began; int replies; boolean started; }
    public static boolean allow(ServerPlayer player) { return allow(player.connection.getConnection().channel(), System.nanoTime()); }
    public static boolean allow(Channel channel, long now) {
        if (!channel.isActive() || JEDPayloads.closing(channel)) return false;
        var state = channel.attr(KEY).get();
        if (state == null) { var candidate = new Window(); var old = channel.attr(KEY).setIfAbsent(candidate); state = old == null ? candidate : old; }
        synchronized (state) {
            if (!state.started || now - state.began >= 1_000_000_000L) { state.began = now; state.replies = 0; state.started = true; }
            if (state.replies >= PER_SECOND) return false;
            state.replies++; return true;
        }
    }
    private JEDRejectionReplies() {}
}
