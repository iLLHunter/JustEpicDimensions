package justepicdimensions.dimension;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BiConsumer;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.lighting.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Native network codecs, stable entry order, complete catalogue and lighting snapshot, then per-connection ACK. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDimensionSync {
    public static final int FRAME_BYTES = 48 * 1024, MAX_BYTES = 64 * 1024 * 1024;
    /** Protocol four adds the optional per-frame {@code barrier} flag. */
    public static final String PROTOCOL = "4";
    public record Frame(CompoundTag data) implements CustomPacketPayload {
        public Frame { JEDDimensionWire.frame(data); }
        static final Type<Frame> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JustEpicDimensions.ID, "dimension_data"));
        static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC = JEDDimensionWire.codec(FRAME_BYTES + 2048).map(Frame::new, Frame::data);
        public Type<Frame> type() { return TYPE; }
    }
    public record Ack(CompoundTag data) implements CustomPacketPayload {
        public Ack { JEDDimensionWire.ack(data); }
        static final Type<Ack> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(JustEpicDimensions.ID, "dimension_ready"));
        static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC = JEDDimensionWire.codec(1024).map(Ack::new, Ack::data);
        public Type<Ack> type() { return TYPE; }
    }
    public record Prepared(JsonObject registries, Set<ResourceLocation> dimensions) {}
    /** Serialized once on the worker and reused for every current connection and late join. */
    public record Serialized(Prepared prepared, List<byte[]> frames, int size, String digest) { public Serialized { frames = List.copyOf(frames); } }
    private static final class State { long revision, policy = -1; Serialized wire; boolean publishing; }
    private record Encoded(String registry, String id, JsonElement value) {}
    public static final class Preparation {
        private final JsonObject previous;
        private final List<JEDNativeRegistry.Append<?>> appends;
        private final Set<ResourceLocation> dimensions;
        private final ArrayDeque<Runnable> codecs = new ArrayDeque<>();
        private final List<Encoded> encoded = new ArrayList<>();
        private Preparation(MinecraftServer server, JEDRegistryTransaction transaction, ResourceLocation dimension) {
            var state = STATES.get(server); previous = state == null || state.wire == null ? new JsonObject() : state.wire.prepared().registries();
            appends = transaction.appends();
            var keys = new TreeSet<>(JEDDimensionLifecycle.activeDimensions(server)); keys.add(dimension); dimensions = Set.copyOf(keys);
            for (var append : appends) {
                var network = RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream().filter(type -> type.key().equals(append.registry().key())).findFirst();
                if (network.isEmpty()) continue;
                for (var holder : append.added()) codecs.add(() -> encoded.add(new Encoded(append.registry().key().location().toString(), holder.key().location().toString(), encode(network.get(), holder.value(), transaction.ops()))));
            }
        }
        public boolean advance() { var work = codecs.poll(); if (work == null) return false; work.run(); return true; }
        public boolean prepared() { return codecs.isEmpty(); }
        /** Does no native/addon codec work and never reads mutable server collections. */
        @SuppressWarnings("unchecked") public Prepared finish() {
            if (!prepared()) throw new IllegalStateException("Network codecs have not completed");
            var data = previous.deepCopy();
            for (var append : appends) {
                String registry = append.registry().key().location().toString();
                if (encoded.stream().noneMatch(value -> value.registry().equals(registry))) continue;
                var block = data.has(registry) ? data.getAsJsonObject(registry) : new JsonObject(); var layout = new JsonArray();
                for (var holder : (List<Holder.Reference<?>>) append.after()[0]) layout.add(holder.key().location().toString());
                block.add("layout", layout); var entries = block.has("entries") ? block.getAsJsonObject("entries") : new JsonObject();
                for (var value : encoded) if (value.registry().equals(registry)) entries.add(value.id(), value.value());
                block.add("entries", entries); data.add(registry, block);
            }
            return new Prepared(data, dimensions);
        }
    }
    private record Expected(long revision, long policy, String token, String digest) {}
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();
    /** Readable from network threads. Incremented before any runtime registry change can start, and at its end. */
    private static final Map<MinecraftServer, java.util.concurrent.atomic.AtomicLong> EPOCHS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final io.netty.util.AttributeKey<Long> CONFIGURED = io.netty.util.AttributeKey.valueOf("justepicdimensions:configured_registry_epoch");
    private static final Map<net.minecraft.server.network.ServerGamePacketListenerImpl, Expected> EXPECTED = new IdentityHashMap<>();
    @FunctionalInterface public interface ClientReceiver {void accept(Frame frame,net.neoforged.neoforge.network.handling.IPayloadContext context,java.util.function.Consumer<Ack> applied);}
    private static ClientReceiver client = (frame, context, applied) -> {};
    public static void clientReceiver(ClientReceiver receiver) { client = receiver; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK).playToClient(Frame.TYPE, Frame.CODEC, JEDDimensionSync::receive);
        registrar.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK).playToServer(Ack.TYPE, Ack.CODEC, (ack, context) -> {
            try {
                if (!justepicdimensions.network.JEDSyncWatchdog.acknowledge(context.connection().channel(), ack.data().getLong("revision"), ack.data().getLong("policy_revision"), ack.data().getString("token"), ack.data().getString("sha256"))) return;
                JEDDimensionWriteGate.acknowledged(context.connection().channel(), ack.data().getString("token"));
            }
            catch (RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("JED rejected an invalid dimension acknowledgment", error);
                justepicdimensions.network.JEDPayloads.disconnectPlay(context.connection().channel(), justepicdimensions.JEDText.tr("connection.sync", justepicdimensions.JEDText.error(error))); return;
            }
            context.enqueueWork(() -> {
            if (!justepicdimensions.network.JEDPayloads.current(context)) return;
            try { acknowledge((ServerPlayer) context.player(), ack.data()); }
            catch (RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().warn("JED rejected an invalid dimension acknowledgment", error);
                justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.sync", justepicdimensions.JEDText.error(error)));
            }
        }); });
    }
    private static void receive(Frame frame, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        var channel = context.connection().channel();
        // Without a barrier the server has not held following traffic: this client already received these
        // registry entries during configuration, and main-thread FIFO keeps the snapshot ahead of later packets.
        boolean barrier = JEDDimensionWire.barrier(frame.data());
        if (barrier && frame.data().getInt("index") == 0) JEDDimensionDecodeGate.receiving(context.connection());
        boolean last = frame.data().getInt("index") == frame.data().getInt("count") - 1;
        // Automatic and explicit reads must both wait. Netty decoders can request a manual read from
        // channelReadComplete even while auto-read is false. Never block the event loop on client work.
        Runnable release = last && barrier ? JEDDimensionDecodeGate.pause(context.connection()) : null;
        try {
            context.enqueueWork(() -> client.accept(frame, context,ack->{
                if(!last)throw new IllegalStateException("Registry acknowledgement before the final frame");
                // Open the client decoder on its event loop before the server can release world traffic.
                resume(channel,release,()->{if(channel.isActive()&&!justepicdimensions.network.JEDPayloads.closing(channel))context.reply(ack);});
            })).whenComplete((ignored, error) -> resume(channel, release));
        } catch (RuntimeException | Error error) {
            // enqueueWork may fail synchronously during shutdown or when already on the main thread.
            resume(channel, release); throw error;
        }
    }
    private static void resume(io.netty.channel.Channel channel, Runnable release) {
        resume(channel,release,null);
    }
    private static void resume(io.netty.channel.Channel channel,Runnable release,Runnable after){
        if (release == null && after == null) return;
        Runnable done=()->{if(release!=null)release.run();if(after!=null)after.run();};
        if (channel.eventLoop().inEventLoop()) done.run();
        else try { channel.eventLoop().execute(done); }
        catch (java.util.concurrent.RejectedExecutionException error) {
            // A terminated loop cannot publish queued native packets. Never leave a live connection held.
            channel.close();
        }
    }
    public static Prepared prepare(MinecraftServer server, JEDRegistryTransaction transaction, ResourceLocation dimension) {
        var work = incremental(server, transaction, dimension); while (work.advance()) {} return work.finish();
    }
    public static Preparation incremental(MinecraftServer server, JEDRegistryTransaction transaction, ResourceLocation dimension) { return new Preparation(server, transaction, dimension); }
    @SuppressWarnings("unchecked") private static <T> JsonElement encode(RegistryDataLoader.RegistryData<T> type, Object value, RegistryOps<JsonElement> ops) {
        return type.elementCodec().encodeStart(ops, (T) value).getOrThrow(error -> new JEDDefinitionException("Native network codec " + type.key() + ": " + error));
    }
    public static void commit(MinecraftServer server, Prepared prepared) {
        commit(server, serialize(prepared));
    }
    public static Serialized serialize(Prepared prepared) {
        var body = new JsonObject(); body.add("registries", prepared.registries()); var levels = new JsonArray();
        new TreeSet<>(prepared.dimensions()).forEach(id -> levels.add(id.toString())); body.add("dimensions", levels);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new JEDDefinitionException("Runtime dimension snapshot too large");
        var frames = new ArrayList<byte[]>();
        for (int i = 0; i < bytes.length; i += FRAME_BYTES) frames.add(Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + FRAME_BYTES)));
        return new Serialized(prepared, frames, bytes.length, digest(bytes));
    }
    /** Registry layouts remain append-only; the active dimension catalogue is a complete replacement. */
    public static Prepared catalogue(MinecraftServer server) {
        var state = STATES.get(server);
        return new Prepared(state == null || state.wire == null ? new JsonObject() : state.wire.prepared().registries(), JEDDimensionLifecycle.activeDimensions(server));
    }
    public static long revision(MinecraftServer server) { var state = STATES.get(server); return state == null ? 0 : state.revision; }
    /** Compare before introducing a readiness barrier; an ordinary reset changes no registry IDs. */
    public static boolean needsCataloguePublication(MinecraftServer server) {
        var state = STATES.get(server);
        return state == null || state.wire == null || !state.wire.prepared().dimensions().equals(JEDDimensionLifecycle.activeDimensions(server))
            || state.policy != JEDLightPolicySync.current(server).orElseThrow().revision();
    }
    public static boolean publishing(MinecraftServer server) { var state = STATES.get(server); return state != null && state.publishing; }
    public static Set<ResourceKey<net.minecraft.world.level.Level>> activeKeys(JsonArray dimensions) {
        var result = new HashSet<ResourceKey<net.minecraft.world.level.Level>>();
        for (var dimension : dimensions) {
            var id = JEDDimensionDefinition.id(JEDJson.string(dimension,"active dimension"),"active catalogue");
            if (!result.add(ResourceKey.create(Registries.DIMENSION,id))) throw new JEDDefinitionException("Duplicate active dimension " + id);
        }
        if (!result.contains(net.minecraft.world.level.Level.OVERWORLD)) throw new JEDDefinitionException("Active catalogue omitted the Overworld");
        return Set.copyOf(result);
    }
    /** Reserve travel before native callbacks can expose the new entries. Each client's deadline starts when its snapshot is sent. */
    public static void beginPublication(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("JED registry publication must begin on the server thread");
        bump(server);
        var state = STATES.computeIfAbsent(server, ignored -> new State());
        state.publishing = true;
        for (var player : server.getPlayerList().getPlayers()) prepare(player, state);
    }
    private static void prepare(ServerPlayer player, State state) {
        JEDPlayerReadiness.hold(player, state.revision + 1, Long.MAX_VALUE);
        JEDDimensionWriteGate.preparing(player.connection.getConnection().channel(), player.registryAccess());
    }
    private static void bump(MinecraftServer server) { EPOCHS.computeIfAbsent(server, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet(); }
    static long epoch(MinecraftServer server) { var value = EPOCHS.get(server); return value == null ? 0 : value.get(); }
    public static void commit(MinecraftServer server, Serialized prepared) {
        long policy = JEDLightPolicySync.current(server).orElseThrow().revision();
        var state = STATES.computeIfAbsent(server, ignored -> new State()); state.wire = prepared; state.revision++;
        state.policy = policy; state.publishing = false; bump(server);
        for (var player : server.getPlayerList().getPlayers()) sendOrDisconnect(player);
    }
    /**
     * A publication failed between {@link #beginPublication} and {@link #commit}. Re-send the last committed snapshot
     * and the current lighting policy with a fresh token, replacing every connection's preparation barrier.
     * Clients converge on the committed state instead of waiting for a commit that will never arrive.
     */
    public static void abortPublication(MinecraftServer server, Throwable cause) {
        if (!server.isSameThread()) throw new IllegalStateException("JED publication abort requires the server thread");
        var state = STATES.get(server);
        if (state == null || !state.publishing) return;
        state.publishing = false; bump(server);
        if (!server.isRunning()) return;
        var players = server.getPlayerList().getPlayers();
        com.mojang.logging.LogUtils.getLogger().warn("JED registry publication failed; restoring revision {} for {} connected player(s)", state.revision, players.size(), cause);
        for (var player : List.copyOf(players)) sendOrDisconnect(player);
    }
    private static void sendOrDisconnect(ServerPlayer player) {
        try { send(player); }
        catch (RuntimeException error) {
            // Never leave one connection held behind a snapshot it cannot receive.
            com.mojang.logging.LogUtils.getLogger().error("JED could not send the dimension snapshot to {}", player.getGameProfile().getName(), error);
            justepicdimensions.network.JEDPayloads.disconnectPlay(player.connection.getConnection().channel(), justepicdimensions.JEDText.tr("connection.sync", justepicdimensions.JEDText.error(error)));
        }
    }
    private static void send(ServerPlayer player) { send(player, true); }
    /** {@code hold}: keep later native traffic on this connection behind the snapshot until the client ACKs it. */
    private static void send(ServerPlayer player, boolean hold) {
        var state = STATES.computeIfAbsent(player.server, ignored -> new State());
        var policy = JEDLightPolicySync.current(player.server).orElseThrow();
        if (state.wire == null) { state.wire = serialize(catalogue(player.server)); state.policy = policy.revision(); }
        var wire = state.wire; String token = UUID.randomUUID().toString(); int count = wire.frames().size();
        EXPECTED.put(player.connection, new Expected(state.revision, policy.revision(), token, wire.digest())); JEDPlayerReadiness.require(player, state.revision, policy.revision());
        // Build frames lazily on this connection's event loop. Native writes behind the batch retain order.
        long revision = state.revision;
        justepicdimensions.network.JEDSyncWatchdog.expect(player.connection.getConnection().channel(), revision, policy.revision(), token, wire.digest());
        JEDDimensionWriteGate.send(player.connection.getConnection().channel(), new JEDDimensionWriteGate.Batch(new Iterator<Object>() {
            private int index = -1;
            public boolean hasNext() { return index < count; }
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                if (index++ == -1) return policy.toVanillaClientbound();
                int part = index - 1;
                var frame = new CompoundTag(); frame.putString("token", token); frame.putString("sha256", wire.digest()); frame.putInt("index", part); frame.putInt("count", count); frame.putInt("size", wire.size());
                frame.putBoolean("barrier", hold); frame.putLong("revision", revision); frame.putLong("policy_revision", policy.revision());
                frame.putByteArray("bytes", wire.frames().get(part)); return new Frame(frame).toVanillaClientbound();
            }
        }, wire.size()),hold ? token : null,player.registryAccess()).addListener((io.netty.channel.ChannelFutureListener) future -> {
            if (future.isSuccess()) justepicdimensions.network.JEDSyncWatchdog.transmitted(future.channel(), token);
        });
    }
    static void requireDeadline(ServerPlayer player, long revision, long policy) {
        long seconds = JEDDefinitionManager.state(player.server).prepared().dimensionSyncTimeoutSeconds();
        var listener = player.connection; var channel = listener.getConnection().channel();
        // No Netty channel (fake players, in-memory test connections): no client will acknowledge, so no deadline.
        if (channel == null) return;
        String name = player.getGameProfile().getName(), uuid = player.getUUID().toString();
        justepicdimensions.network.JEDSyncWatchdog.require(channel, seconds, revision, policy, status -> {
            justepicdimensions.network.JEDPayloads.markClosing(channel);
            com.mojang.logging.LogUtils.getLogger().warn("JED dimension sync timed out for {} ({}) after {} ms: stage={}, revision={}, policy={}, token={}, backlog={}. Disconnecting this player and cancelling pending travel/respawn.", name, uuid, status.elapsedMillis(), status.stage(), status.revision(), status.policy(), status.token(), JEDDimensionWriteGate.backlog(channel));
            var reason = justepicdimensions.JEDText.tr("connection.sync", net.minecraft.network.chat.Component.literal("Dimension synchronization timed out after " + seconds + " seconds (" + status.stage() + "). Reconnect to try again."));
            // Never call ServerCommonPacketListenerImpl.disconnect on Netty: its executeBlocking waits for the server.
            try { justepicdimensions.network.JEDPayloads.disconnectPlay(channel, reason); }
            finally {
                try { player.server.execute(() -> { EXPECTED.remove(listener); JEDPlayerReadiness.logout(listener); }); }
                catch (java.util.concurrent.RejectedExecutionException error) { channel.close(); } // ServerStopped also clears readiness.
            }
        });
    }
    private static void acknowledge(ServerPlayer player, CompoundTag ack) {
        var expected = EXPECTED.get(player.connection);
        if (expected == null || !expected.token().equals(ack.getString("token"))) return; // Superseded transaction/connection.
        if (ack.getLong("revision") != expected.revision() || ack.getLong("policy_revision") != expected.policy() || !expected.digest().equals(ack.getString("sha256")))
            throw new IllegalArgumentException("JED dimension readiness acknowledgment does not match the sent snapshot");
        EXPECTED.remove(player.connection);JEDPlayerReadiness.ready(player, expected.revision(), expected.policy());
    }
    public static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    /** Configuration start, possibly on a network thread. Native registry data sent later in this phase includes every entry published before this epoch. */
    public static void configuring(net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent event) {
        var listener = event.getListener();
        if (listener.getMainThreadEventLoop() instanceof MinecraftServer server) listener.getConnection().channel().attr(CONFIGURED).set(epoch(server));
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var channel = player.connection.getConnection().channel(); Long configured = channel.attr(CONFIGURED).getAndSet(null);
        var state = STATES.get(player.server);
        if (state != null && state.publishing) {
            // The pending commit (or abort) sends this connection its snapshot. Hold callback traffic until then.
            prepare(player, state); JEDPlayerReadiness.shieldLogin(player); return;
        }
        // Configuration already delivered every native registry entry unless a publication started since then.
        boolean current = configured != null && configured == epoch(player.server);
        send(player, !current);
        if (!current) JEDPlayerReadiness.shieldLogin(player);
    }
    /** A held login receives no world updates. Keep the player from being hurt or targeted until the client is ready. */
    @SubscribeEvent public static void shieldDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && JEDPlayerReadiness.shielded(player) && !event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) event.setCanceled(true);
    }
    @SubscribeEvent public static void shieldTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && JEDPlayerReadiness.shielded(player)) event.setCanceled(true);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) { if (event.getEntity() instanceof ServerPlayer player) { EXPECTED.remove(player.connection); JEDPlayerReadiness.logout(player); } }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { STATES.remove(event.getServer()); EPOCHS.remove(event.getServer()); EXPECTED.keySet().removeIf(connection -> connection.player.server == event.getServer()); JEDPlayerReadiness.stopped(event.getServer()); }
    private JEDDimensionSync() {}
}
