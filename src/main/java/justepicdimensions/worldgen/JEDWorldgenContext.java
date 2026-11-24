package justepicdimensions.worldgen;

import com.mojang.logging.LogUtils;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.world.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Immutable generation inputs attached before native chunk/structure initialization. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public record JEDWorldgenContext(long seed, boolean structures, JEDWorldgenDefinition requested, boolean localBorder) {
    private static final Map<RegistryAccess,Map<ResourceLocation,JEDWorldgenContext>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());
    public static long prepare(MinecraftServer server, ResourceKey<Level> key, long nativeObfuscated) {
        var definition = JEDDefinitionManager.state(server).definitions().get(key.location());
        var requested = definition == null ? JEDWorldgenDefinition.DEFAULT : definition.generation();
        var options = server.getWorldData().worldGenOptions();
        long seed = options.seed();
        var world = server.getWorldPath(LevelResource.ROOT);
        var templateOptions=justepicdimensions.structure.JEDTemplateManager.generation(world,key.location());
        if(templateOptions!=null)seed=templateOptions.seed();
        var previous = JEDDimensionStore.read(world, key.location(), "worldgen_state");
        if(previous!=null&&(!previous.contains("seed",net.minecraft.nbt.Tag.TAG_LONG)||!previous.contains("structures",net.minecraft.nbt.Tag.TAG_BYTE)||!previous.contains("selection",net.minecraft.nbt.Tag.TAG_STRING)))throw new JEDDefinitionException("Incomplete JED generation record for "+key.location());
        if (requested.seed() != null) {
            if (requested.seed().equals("random")) seed = previous != null && previous.getString("selection").equals("random") ? previous.getLong("seed") : net.minecraft.util.RandomSource.create().nextLong();
            else seed = WorldOptions.parseSeed(requested.seed()).orElseThrow();
        }
        if(previous==null&&templateOptions!=null&&seed!=templateOptions.seed())LogUtils.getLogger().warn("JED {} template seed {} overridden by configured seed {}. Imported chunks remain unchanged; new chunks can form seams or discontinuous structures.",key.location(),templateOptions.seed(),seed);
        boolean structures = requested.structures() == null ? (templateOptions==null?options.generateStructures():templateOptions.generateStructures()) : requested.structures();
        var context = new JEDWorldgenContext(seed, structures, requested, JEDWorldControl.options(server).borders());
        if (previous != null && (previous.getLong("seed") != seed || previous.getBoolean("structures") != structures))
            LogUtils.getLogger().warn("JED {} generation settings changed on safe load: seed {} -> {}, structures {} -> {}. Existing chunks remain unchanged; new chunks can form seams or discontinuous structures.", key.location(), previous.getLong("seed"), seed, previous.getBoolean("structures"), structures);
        if (requested != JEDWorldgenDefinition.DEFAULT || previous != null) {
            var state = new CompoundTag(); state.putLong("seed", seed); state.putBoolean("structures", structures); state.putString("selection", requested.seed() == null ? "inherit" : requested.seed());
            JEDDimensionStore.save(world, key.location(), "worldgen_state", state);
        }
        synchronized(PENDING) { PENDING.computeIfAbsent(server.registryAccess(), ignored -> new HashMap<>()).put(key.location(), context); }
        return requested.seed() == null && templateOptions==null ? nativeObfuscated : BiomeManager.obfuscateSeed(seed);
    }
    public static void bind(ServerLevel level) {
        JEDWorldgenContext context;
        synchronized(PENDING) { context = PENDING.getOrDefault(level.registryAccess(), Map.of()).remove(level.dimension().location()); }
        if (context == null) throw new IllegalStateException("JED generation construction context missing: " + level.dimension().location());
        ((JEDWorldgenAccess)(Object)level).jed$worldgen(context);
    }
    public static boolean pendingBorder(RegistryAccess registries, ResourceKey<Level> key) {
        synchronized(PENDING) { var c = PENDING.getOrDefault(registries, Map.of()).get(key.location()); return c != null && c.localBorder(); }
    }
    public static JEDWorldgenContext context(ServerLevel level) { return ((JEDWorldgenAccess)(Object)level).jed$worldgen(); }
    public static long seed(long nativeSeed, ServerLevel level) { var c = context(level); return c == null ? nativeSeed : c.seed(); }
    public static WorldOptions options(WorldOptions nativeOptions, ServerLevel level) {
        var c = context(level); return c == null ? nativeOptions : new WorldOptions(c.seed(), c.structures(), nativeOptions.generateBonusChest());
    }
    public static boolean structures(boolean nativeValue,net.minecraft.world.level.chunk.status.WorldGenContext generation){var c=context(generation.level());return c==null?nativeValue:c.structures();}
    public static void publish(MinecraftServer server, Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        for (var level : server.getAllLevels()) {
            var c = context(level); var d = definitions.get(level.dimension().location()); var next = d == null ? JEDWorldgenDefinition.DEFAULT : d.generation();
            if (c != null && !c.requested().equals(next)) LogUtils.getLogger().warn("JED {} has pending generation settings. The active seed/generator context remains unchanged until the next safe dimension load (Overworld: reopen the world). Existing chunks will not be regenerated; new chunks may form seams.", level.dimension().location());
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { synchronized(PENDING) { PENDING.remove(event.getServer().registryAccess()); } }
}
