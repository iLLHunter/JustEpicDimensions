package justepicdimensions.network;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Event-loop-owned application deadline. Native traffic never renews an unfinished synchronization. */
public final class JEDSyncWatchdog {
    private static final AttributeKey<JEDSyncWatchdog> KEY = AttributeKey.valueOf("justepicdimensions:sync_deadline");
    public record Status(long elapsedMillis, long revision, long policy, String token, String stage) {}
    private final Channel channel;
    private long started, duration;
    private Consumer<Status> expired;
    private ScheduledFuture<?> timer;
    private long revision, policy;
    private String token = "", digest = "", stage = "preparing publication";
    private boolean finished;
    private JEDSyncWatchdog(Channel channel) {
        this.channel = channel; finished = true;
        channel.closeFuture().addListener(ignored -> cancel());
    }
    private void start(long seconds, long requestedAt, Consumer<Status> expired) {
        started = requestedAt; duration = TimeUnit.SECONDS.toNanos(seconds); this.expired = expired; finished = false;
        if (seconds > 0) timer = channel.eventLoop().schedule(this::expire, Math.max(0, duration - (System.nanoTime() - started)), TimeUnit.NANOSECONDS);
    }
    public static void require(Channel channel, long seconds, long revision, long policy, Consumer<Status> expired) {
        long requestedAt = System.nanoTime();
        execute(channel, () -> {
            if (!channel.isActive() || JEDPayloads.closing(channel)) return;
            var state = channel.attr(KEY).get();
            if (state == null) { state = new JEDSyncWatchdog(channel); channel.attr(KEY).set(state); }
            if (state.finished) state.start(seconds, requestedAt, expired);
            state.revision = revision; state.policy = policy; state.token = ""; state.digest = "";
            state.stage = "preparing publication";
        });
    }
    public static void expect(Channel channel, long revision, long policy, String token, String digest) {
        execute(channel, () -> {
            var state = channel.attr(KEY).get();
            if (state == null || state.finished) return;
            state.revision = revision; state.policy = policy; state.token = token; state.digest = digest;
            state.stage = "transmitting snapshot";
        });
    }
    public static void transmitted(Channel channel, String token) {
        execute(channel, () -> {
            var state = channel.attr(KEY).get();
            if (state != null && !state.finished && state.token.equals(token)) state.stage = "waiting for client application/ACK";
        });
    }
    /** Called on the network loop, before main-thread admission. A received matching ACK wins there. */
    public static boolean acknowledge(Channel channel, long revision, long policy, String token, String digest) {
        if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("ACK admission requires the event loop");
        var state = channel.attr(KEY).get();
        if (!channel.isActive() || JEDPayloads.closing(channel) || state == null || state.finished || !state.token.equals(token)) return false;
        // A busy loop may process socket reads before its due timers. A late ACK cannot win that race.
        if (state.duration > 0 && System.nanoTime() - state.started >= state.duration) { state.expire(); return false; }
        if (state.revision != revision || state.policy != policy || !state.digest.equals(digest))
            throw new IllegalArgumentException("JED dimension readiness acknowledgment does not match the sent snapshot");
        state.cancel();
        return true;
    }
    private void expire() {
        if (finished || !channel.isActive()) return;
        finished = true; timer = null;
        var callback = expired; expired = null;
        callback.accept(new Status(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), revision, policy, token, stage));
    }
    private void cancel() { finished = true; expired = null; if (timer != null) { timer.cancel(false); timer = null; } }
    public static void execute(Channel channel, Runnable action) {
        try { if (channel.eventLoop().inEventLoop()) action.run(); else channel.eventLoop().execute(action); }
        catch (java.util.concurrent.RejectedExecutionException error) { channel.close(); }
    }
}
