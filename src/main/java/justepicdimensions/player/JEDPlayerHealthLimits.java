package justepicdimensions.player;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.*;

/** Transient native attributes carry group rules to clients without replacing permanent modifiers. */
public final class JEDPlayerHealthLimits {
    public static final ResourceLocation CAP = id("group_health_cap");
    public static final ResourceLocation FIXED = id("group_health_fixed");
    public static final ResourceLocation ABSORPTION = id("group_absorption_capacity");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("justepicdimensions", path);
    }

    private static boolean control(ResourceLocation id) {
        return CAP.equals(id) || FIXED.equals(id) || ABSORPTION.equals(id);
    }

    /** Called only by the version-checked native attribute bridge. */
    public static Collection<AttributeModifier> modifiers(Collection<AttributeModifier> nativeModifiers) {
        for (var modifier : nativeModifiers) if (control(modifier.id())) {
            var ordinary = new ArrayList<AttributeModifier>(nativeModifiers.size());
            for (var candidate : nativeModifiers) if (!control(candidate.id())) ordinary.add(candidate);
            return ordinary;
        }
        return nativeModifiers;
    }

    /** Native calculation, including other mods' modifiers and sanitization, has already completed. */
    public static double maximum(double nativeMaximum, AttributeInstance instance) {
        var fixed = instance.getModifier(FIXED);
        var cap = instance.getModifier(CAP);
        var absorption = instance.getModifier(ABSORPTION);
        if (fixed != null && instance.getAttribute().value()==Attributes.MAX_HEALTH.value())
            return instance.getAttribute().value().sanitizeValue(fixed.amount());
        if (cap != null && instance.getAttribute().value()==Attributes.MAX_HEALTH.value())
            return Math.min(nativeMaximum, instance.getAttribute().value().sanitizeValue(cap.amount()));
        if (absorption != null && instance.getAttribute().value()==Attributes.MAX_ABSORPTION.value())
            return Math.max(nativeMaximum, instance.getAttribute().value().sanitizeValue(absorption.amount()));
        return nativeMaximum;
    }

    public static void health(ServerPlayer player, String mode, double hearts) {
        var attribute = Objects.requireNonNull(player.getAttribute(Attributes.MAX_HEALTH));
        ResourceLocation selected = switch (mode) {
            case "follow_mods" -> null;
            case "cap" -> CAP;
            case "fixed" -> FIXED;
            default -> throw new IllegalArgumentException("Unknown maximum-health mode " + mode);
        };
        double amount = hearts * 2;
        if (selected != null && (!Double.isFinite(amount) || amount <= 0
                || attribute.getAttribute().value().sanitizeValue(amount) != amount))
            throw new IllegalArgumentException("Configured maximum health is outside the native attribute range");
        for (var id : List.of(CAP, FIXED)) if (!id.equals(selected)) attribute.removeModifier(id);
        if (selected != null) set(attribute, selected, amount);
    }

    /** Retained absorption can outlive the effect/equipment which originally supplied its capacity. */
    public static void absorption(ServerPlayer player, float amount) {
        var attribute = Objects.requireNonNull(player.getAttribute(Attributes.MAX_ABSORPTION));
        if (amount > 0) set(attribute, ABSORPTION, amount);
        else attribute.removeModifier(ABSORPTION);
    }

    private static void set(AttributeInstance instance, ResourceLocation id, double amount) {
        var previous = instance.getModifier(id);
        if (previous == null || previous.amount() != amount
                || previous.operation() != AttributeModifier.Operation.ADD_VALUE)
            instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    private JEDPlayerHealthLimits() {}
}
