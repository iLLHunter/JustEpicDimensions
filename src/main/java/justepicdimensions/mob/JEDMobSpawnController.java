package justepicdimensions.mob;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.JEDDimensionDefinition;
import justepicdimensions.world.JEDWorldControl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Resolve flags at their native assignment point; do not replace native spawning or entity checks. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDMobSpawnController {
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDMobSpawnDefinition>> servers=Map.of();
    private static final ThreadLocal<ServerLevel> NATURAL_LEVEL=new ThreadLocal<>();
    /** Scope the native call, including nested addon calls and exceptions; never change the global category. */
    public static ServerLevel enterNatural(ServerLevel level){var previous=NATURAL_LEVEL.get();NATURAL_LEVEL.set(level);return previous;}
    public static void exitNatural(ServerLevel previous){if(previous==null)NATURAL_LEVEL.remove();else NATURAL_LEVEL.set(previous);}
    private static Integer creatureCap(net.minecraft.world.entity.MobCategory category){
        if(category!=net.minecraft.world.entity.MobCategory.CREATURE)return null;
        var level=NATURAL_LEVEL.get();var policy=level==null?null:policy(level);return policy==null?null:policy.creatureCap();
    }
    public static int localCap(int inherited,net.minecraft.world.entity.MobCategory category){var cap=creatureCap(category);return cap==null?inherited:cap;}
    public static int globalCap(int inherited,net.minecraft.world.entity.MobCategory category,int chunks,int divisor){
        var cap=creatureCap(category);return cap==null?inherited:(int)Math.min(Integer.MAX_VALUE,(long)cap*chunks/divisor);
    }
    public static JEDMobSpawnDefinition policy(ServerLevel level) {return servers.getOrDefault(level.getServer(),Map.of()).get(level.dimension().location());}
    public static synchronized void publish(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        var policies=new HashMap<ResourceLocation,JEDMobSpawnDefinition>();
        definitions.forEach((id,definition)->{var policy=definition.mobs();if(policy!=null)policies.put(id,policy);});
        var previous=servers.getOrDefault(server,Map.of());var next=new IdentityHashMap<>(servers);next.put(server,Map.copyOf(policies));servers=Collections.unmodifiableMap(next);
        for(var level:server.getAllLevels())if(!sameFlags(previous.get(level.dimension().location()),policies.get(level.dimension().location())))
            JEDWorldControl.spawnSettings(level,server.isSpawningMonsters(),server.isSpawningAnimals());
    }
    private static boolean sameFlags(JEDMobSpawnDefinition before,JEDMobSpawnDefinition after){
        return Objects.equals(before==null?null:before.hostile(),after==null?null:after.hostile())&&Objects.equals(before==null?null:before.peaceful(),after==null?null:after.peaceful());
    }
    public static boolean hostile(boolean nativeValue,ServerLevel level) {
        var policy=policy(level);
        return policy==null||policy.hostile()==null?nativeValue:policy.hostile()&&level.getDifficulty()!=Difficulty.PEACEFUL;
    }
    public static boolean peaceful(boolean nativeValue,ServerLevel level) {
        var policy=policy(level);return policy==null||policy.peaceful()==null?nativeValue:policy.peaceful();
    }
    /** An explicit allow must also survive the native spawn-animals=false removal check.
     * A dimension spawn veto is never turned into a new rule for killing existing animals. */
    public static boolean animalsRetained(boolean nativeValue,ServerLevel level) {
        if(nativeValue)return true;var policy=policy(level);return policy!=null&&Boolean.TRUE.equals(policy.peaceful());
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event) {
        var next=new IdentityHashMap<>(servers);next.remove(event.getServer());servers=Collections.unmodifiableMap(next);
    }
    private JEDMobSpawnController() {}
}
