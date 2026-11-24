package justepicdimensions.definition;

import com.mojang.serialization.Lifecycle;
import java.util.Map;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/**
 * Vanilla classes every registry entry from a pack it does not know, and every extra dimension, as "experimental".
 * Single-player then warns "Worlds using Experimental Settings are not supported" when such a world is opened or created.
 * Definitions JED itself serves (config JSON including the showcase, saved runtime creations and managed instances) are
 * supported, so their entries are reported as stable. Entries from other mods' packs or plain datapacks keep their native
 * lifecycle and therefore still produce the native warning. Lifecycles are informational: nothing else about the
 * entries, their registration order or network synchronization changes.
 */
public final class JEDRegistryStability {
    private JEDRegistryStability() {}

    static boolean owned(JEDSourceDocument document) {
        return document != null && document.origins().values().stream()
            .anyMatch(source -> source.startsWith("JSON ") || source.startsWith("saved creation ") || source.startsWith("managed instance "));
    }

    private static boolean owned(Map<ResourceLocation, JEDSourceDocument> resources, String directory, ResourceLocation id) {
        return owned(resources.get(id.withPath(directory + "/" + id.getPath() + ".json")));
    }

    /** Worldgen layer, immediately after native decoding and before any lifecycle is read. */
    public static void worldgen(RegistryAccess access, JEDPreparedDefinitions prepared) {
        access.registries().forEach(entry -> stabilize(entry.value(), prepared.resources()));
    }

    private static <T> void stabilize(Registry<T> registry, Map<ResourceLocation, JEDSourceDocument> resources) {
        if (!(registry instanceof MappedRegistry<T> mapped) || mapped.registryLifecycle() == Lifecycle.stable()) return;
        String directory = Registries.elementsDirPath(registry.key());
        boolean changed = false;
        for (var entry : mapped.registrationInfos.entrySet()) {
            var info = entry.getValue();
            if (info.lifecycle() != Lifecycle.stable() && owned(resources, directory, entry.getKey().location())) {
                entry.setValue(new RegistrationInfo(info.knownPackInfo(), Lifecycle.stable()));
                changed = true;
            }
        }
        if (!changed) return;
        // Decoded registries start stable; their lifecycle is the combination of their entries' lifecycles.
        Lifecycle lifecycle = Lifecycle.stable();
        for (var info : mapped.registrationInfos.values()) lifecycle = lifecycle.add(info.lifecycle());
        mapped.registryLifecycle = lifecycle;
    }

    /**
     * Final baked dimensions. Vanilla marks each extra stem experimental and the whole registry experimental whenever it
     * does not hold exactly the three vanilla dimensions. When JED-owned stems are the only reason, publish it as stable.
     */
    public static WorldDimensions.Complete dimensions(WorldDimensions.Complete complete, JEDPreparedDefinitions prepared) {
        Registry<LevelStem> old = complete.dimensions();
        if (old.registryLifecycle() == Lifecycle.stable()) return complete;
        var resources = prepared.resources();
        for (ResourceKey<LevelStem> key : old.registryKeySet()) {
            var info = old.registrationInfo(key).orElseThrow();
            if (info.lifecycle() != Lifecycle.stable() && !owned(resources, "dimension", key.location())) return complete;
        }
        var stable = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable());
        old.holders().forEach(holder -> {
            var info = old.registrationInfo(holder.key()).orElseThrow();
            stable.register(holder.key(), holder.value(), info.lifecycle() == Lifecycle.stable() ? info : new RegistrationInfo(info.knownPackInfo(), Lifecycle.stable()));
        });
        return new WorldDimensions.Complete(stable.freeze(), complete.specialWorldProperty());
    }
}
