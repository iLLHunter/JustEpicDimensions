package justepicdimensions.api.access;

import java.util.Optional;
import java.util.Set;
import justepicdimensions.access.JEDAccessController;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server-side access checks for other mods. Call on the server thread only.
 * <p>
 * Results match what JED itself enforces: {@link #denial} is the same check JED runs when a mod moves an entity into another
 * dimension, so a caller can ask before moving anyone and keep a travelling group together instead of having JED refuse
 * part-way through.
 */
public final class JEDAccess {
    /**
     * Why {@code root} and everything riding with it may not enter {@code destination} by ordinary travel, or empty when
     * all of them may. Same-dimension movement is always allowed. Explicit bypasses are not applied.
     */
    public static Optional<Component> denial(Entity root, ResourceLocation destination) {
        var decision = JEDAccessController.group(root, destination, false);
        return decision.allowed() ? Optional.empty() : Optional.of(decision.reason());
    }

    /** The outcome of one rule: when not allowed, {@code reason} names the failed conditions. */
    public record Result(boolean allowed, Component reason) {}

    /**
     * Evaluates one active reusable access rule ({@code data/<ns>/justepicdimensions/access_rules/<name>.json}) for a player,
     * or returns empty when no rule with that id is active. A rule that cannot be evaluated denies, with a reason saying so.
     */
    public static Optional<Result> evaluateRule(ServerPlayer player, ResourceLocation rule) {
        return JEDAccessController.evaluateRule(player, rule).map(decision -> new Result(decision.allowed(), decision.reason()));
    }

    /** Ids of the reusable access rules currently active on this server. */
    public static Set<ResourceLocation> ruleIds(MinecraftServer server) { return JEDAccessController.ruleIds(server); }

    private JEDAccess() {}
}
