package justepicdimensions.world;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.worldgen.JEDWorldgenContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.EventPriority;

@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDBorderController {
    private static final Map<ResourceLocation,Boolean> CLIENT = new HashMap<>();
    private static final Set<ServerLevel> WAITING = Collections.newSetFromMap(new WeakHashMap<>());
    public static void client(ResourceLocation id, boolean local) { CLIENT.put(id, local); }
    public static void clearClient() { CLIENT.clear(); }
    public static WorldBorder construct(WorldBorder original, RegistryAccess registries, ResourceKey<Level> key, boolean client) {
        boolean local=client?CLIENT.getOrDefault(key.location(),false):JEDWorldgenContext.pendingBorder(registries,key);
        // Replace only Minecraft's scaled anonymous border, preserving ordinary/custom implementations.
        return local&&original.getClass().getName().equals("net.minecraft.world.level.Level$1")?new WorldBorder():original;
    }
    private static JEDBorderDefinition policy(ServerLevel level) { var d=JEDDefinitionManager.state(level.getServer()).definitions().get(level.dimension().location());return d==null?JEDBorderDefinition.DEFAULT:d.border(); }
    public static void validate(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        for(var d:definitions.values()) if(d.border().centerOnSpawn()&&!candidate.worldOptions().borders()&&!d.id().equals(Level.OVERWORLD.location()))
            throw new JEDDefinitionException(d.id()+": border.center_on_spawn requires enableSeparateWorldBorders for a secondary dimension");
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void loaded(LevelEvent.Load event) {
        if(event.getLevel() instanceof ServerLevel level) { WAITING.add(level); justepicdimensions.spawn.JEDSpawnController.prepare(level).thenRun(()->ready(level)); }
    }
    public static void ready(ServerLevel level) { if(WAITING.remove(level)&&policy(level).centerOnSpawn())center(level); }
    public static void spawnChanged(ServerLevel level) { var policy=policy(level);if(policy.centerOnSpawn()&&policy.centering().equals("follow_spawn"))center(level); }
    private static void center(ServerLevel level) {
        // Raw saved world spawn avoids the native border-clamped getSharedSpawnPos feedback loop.
        var pos=level.getLevelData().getSpawnPos();var border=level.getWorldBorder();
        if(border.getCenterX()!=pos.getX()||border.getCenterZ()!=pos.getZ())border.setCenter(pos.getX(),pos.getZ());
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { if(event.getLevel() instanceof ServerLevel level)WAITING.remove(level); }
    private JEDBorderController() {}
}
