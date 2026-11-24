package justepicdimensions.dimension;

import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.portal.DimensionTransition;

/** One latest pending action per connection. No player/vehicle/level mutation occurs while waiting. */
public final class JEDPlayerReadiness {
    /** known: the dimensions registered when this client last acknowledged, and knownPolicy that acknowledgment's lighting
     * policy; null until the first acknowledgment. */
    private static final class State { long revision, policy; boolean ready; Runnable pending; AutoCloseable pins; Set<net.minecraft.resources.ResourceLocation> known; long knownPolicy = Long.MIN_VALUE; }
    private static final Map<ServerGamePacketListenerImpl, State> STATES = new IdentityHashMap<>();
    /** Logins whose native traffic is held for a registry ACK. Cleared by readiness or logout. */
    private static final Set<ServerGamePacketListenerImpl> SHIELDED = Collections.newSetFromMap(new IdentityHashMap<>());
    public static void require(ServerPlayer player, long revision, long policy) {
        hold(player, revision, policy);
        JEDDimensionSync.requireDeadline(player, revision, policy);
    }
    /** Defer travel and respawn without starting the client deadline; the deadline starts when a snapshot is sent. */
    public static void hold(ServerPlayer player, long revision, long policy) {
        var state = STATES.computeIfAbsent(player.connection, ignored -> new State()); state.revision = revision; state.policy = policy; state.ready = false;
    }
    public static void shieldLogin(ServerPlayer player) { var state = STATES.get(player.connection); if (state != null && !state.ready) SHIELDED.add(player.connection); }
    public static boolean shielded(ServerPlayer player) {
        if (!SHIELDED.contains(player.connection)) return false;
        var state = STATES.get(player.connection); if (state != null && !state.ready) return true;
        SHIELDED.remove(player.connection); return false;
    }
    public static boolean isReady(ServerPlayer player) { var state = STATES.get(player.connection); return state == null || state.ready; }
    /**
     * Whether this player's travel to target must wait for their client's acknowledgment. A publication only appends
     * registry entries, so every dimension the client acknowledged keeps its IDs and travel between those continues. Only
     * a dimension the client has not received yet, a pending lighting-policy change, or a client that has never
     * acknowledged waits. A null target (respawn) always waits while the player is held.
     */
    public static boolean mustHold(ServerPlayer player, net.minecraft.resources.ResourceLocation target) {
        var state = STATES.get(player.connection);
        if (state == null || state.ready) return false;
        if (state.known == null || target == null) return true;
        if (state.policy != Long.MAX_VALUE && state.policy != state.knownPolicy) return true; // MAX_VALUE: policy not yet chosen
        return !state.known.contains(target);
    }
    public static void ready(ServerPlayer player, long revision, long policy) {
        var state = STATES.get(player.connection); if (state == null || state.revision != revision || state.policy != policy) return;
        // A matching acknowledgment means this client now has every dimension registered at this revision.
        state.known = Set.copyOf(player.server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM).keySet());
        state.knownPolicy = policy;
        state.ready = true; SHIELDED.remove(player.connection); var action = state.pending; var pins = state.pins; state.pending = null; state.pins = null;
        try { if (action != null && player.connection.isAcceptingMessages()) action.run(); } finally { close(pins); }
    }
    private static boolean queue(ServerPlayer player, net.minecraft.resources.ResourceLocation target, Runnable action) {
        var state = STATES.get(player.connection); if (state == null || state.ready) return false;
        var pins = JEDDimensionLifecycle.pinPending(player.server, player.serverLevel().dimension().location(), target);
        close(state.pins); state.pins = pins; state.pending = action; return true;
    }
    private static void close(AutoCloseable value) { if (value != null) try { value.close(); } catch (Exception error) { throw new IllegalStateException("Failed to release pending travel",error); } }
    private static boolean blocked(ServerPlayer player, ServerLevel target) {
        if (JEDDimensionLifecycle.available(player.server,target.dimension().location()) && player.server.levels.get(target.dimension()) == target) return false;
        player.sendSystemMessage(justepicdimensions.JEDText.tr("travel.unavailable")); return true;
    }
    public static boolean defer(ServerPlayer player, DimensionTransition transition) {
        if (blocked(player,transition.newLevel())) return true;
        if (player.serverLevel() == transition.newLevel()) return false;
        if (!mustHold(player, transition.newLevel().dimension().location())) return false;
        var source = player.serverLevel();
        return queue(player, transition.newLevel().dimension().location(), () -> { if (player.connection.player == player && player.serverLevel() == source && !player.isRemoved()) player.changeDimension(transition); });
    }
    public static boolean deferTeleport(ServerPlayer player, ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative, float yaw, float pitch) {
        if (blocked(player,level)) return true;
        if (player.serverLevel() == level) return false;
        if (!mustHold(player, level.dimension().location())) return false;
        var source = player.serverLevel(); var movements = Set.copyOf(relative);
        return queue(player, level.dimension().location(), () -> { if (player.connection.player == player && player.serverLevel() == source && !player.isRemoved()) player.teleportTo(level, x, y, z, movements, yaw, pitch); });
    }
    public static boolean deferDirectTeleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        if (blocked(player,level)) return true;
        if (player.serverLevel() == level) return false;
        if (!mustHold(player, level.dimension().location())) return false;
        var source = player.serverLevel();
        return queue(player, level.dimension().location(), () -> { if (player.connection.player == player && player.serverLevel() == source && !player.isRemoved()) player.teleportTo(level, x, y, z, yaw, pitch); });
    }
    public static boolean deferClientCommand(ServerGamePacketListenerImpl connection, net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet) {
        if (packet.getAction() != net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) return false;
        if(justepicdimensions.spawn.JEDSpawnController.deferClientCommand(connection,packet))return true;
        return queue(connection.player, null, () -> connection.handleClientCommand(packet));
    }
    public static boolean deferRespawn(PlayerList list, ServerPlayer player, boolean keep, Entity.RemovalReason reason) {
        return queue(player, null, () -> { if (player.connection.player == player) player.connection.player = list.respawn(player, keep, reason); });
    }
    public static void logout(ServerPlayer player) { logout(player.connection); }
    public static void logout(ServerGamePacketListenerImpl connection) { SHIELDED.remove(connection); var state = STATES.remove(connection); if (state != null) close(state.pins); }
    public static void stopped(net.minecraft.server.MinecraftServer server) { var players = STATES.keySet().stream().filter(connection -> connection.player.server == server).map(connection -> connection.player).toList(); players.forEach(JEDPlayerReadiness::logout); }
    private JEDPlayerReadiness() {}
}
