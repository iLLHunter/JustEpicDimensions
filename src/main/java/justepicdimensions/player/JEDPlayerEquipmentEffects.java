package justepicdimensions.player;

import java.util.*;
import justepicdimensions.api.player.JEDEquipmentEffectProviders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Resolve ownership from active native equipment, including actual accessory slot counts. */
public final class JEDPlayerEquipmentEffects {
    public static Set<ResourceLocation> owned(ServerPlayer player, Collection<JEDPlayerEquipmentRule> rules) {
        var result = new HashSet<ResourceLocation>();
        var providers = JEDEquipmentEffectProviders.providers();
        if (rules.isEmpty() && providers.isEmpty()) return Set.of();
        for (var adapter : JEDInventoryAdapterRegistry.adapters()) for (var equipment : adapter.equipped(player)) {
            var item = equipment.item();
            for (var rule : rules) if (rule.items().stream().anyMatch(selector -> selector.item(item)))
                BuiltInRegistries.MOB_EFFECT.holders().forEach(effect -> {
                    if (rule.effects().stream().anyMatch(selector -> selector.effect(effect))) result.add(effect.key().location());
                });
            for (var provider : providers.entrySet()) {
                Collection<ResourceLocation> effects;
                try { effects = Objects.requireNonNull(provider.getValue().effects(player, equipment)); }
                catch (RuntimeException error) { throw new IllegalStateException("JED equipment-effect provider " + provider.getKey() + " failed; player state retained", error); }
                for (var id : effects) {
                    if (id == null || !BuiltInRegistries.MOB_EFFECT.containsKey(id))
                        throw new IllegalStateException("JED equipment-effect provider " + provider.getKey() + " returned an unknown effect " + id);
                    result.add(id);
                }
            }
        }
        return Set.copyOf(result);
    }
    private JEDPlayerEquipmentEffects() {}
}
