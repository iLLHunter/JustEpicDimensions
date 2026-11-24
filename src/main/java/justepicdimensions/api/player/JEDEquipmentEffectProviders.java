package justepicdimensions.api.player;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Read-only equipment-effect associations supplied by addons; ordinary equipment behavior remains native. */
public final class JEDEquipmentEffectProviders {
    public record Equipment(String backend, String slot, int index, ItemStack item) {
        public Equipment { Objects.requireNonNull(backend); Objects.requireNonNull(slot); item = item.copy(); }
        @Override public ItemStack item() { return item.copy(); }
    }

    @FunctionalInterface
    public interface Provider {
        /** Return effect IDs owned by this currently equipped item. Do not mutate the player or equipment. */
        Collection<ResourceLocation> effects(ServerPlayer player, Equipment equipment);
    }

    private static final Map<ResourceLocation, Provider> PROVIDERS = new LinkedHashMap<>();

    /** Register once during addon initialization. Provider IDs must be unique. */
    public static synchronized void register(ResourceLocation id, Provider provider) {
        Objects.requireNonNull(id); Objects.requireNonNull(provider);
        if (PROVIDERS.putIfAbsent(id, provider) != null)
            throw new IllegalArgumentException("Duplicate JED equipment-effect provider " + id);
    }

    public static synchronized Map<ResourceLocation, Provider> providers() { return Map.copyOf(PROVIDERS); }
    private JEDEquipmentEffectProviders() {}
}
