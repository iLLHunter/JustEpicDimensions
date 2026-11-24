package justepicdimensions.time;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.JEDDimensionDefinition;
import justepicdimensions.world.JEDWorldSync;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Constant-time per-level dispatch; native simulation ticks and timer queues are untouched. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDTimeController {
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDTimeDefinition>> servers=Map.of();
    private static volatile Map<ResourceLocation,JEDTimeDefinition> clients=Map.of();
    public static JEDTimeDefinition policy(LevelReader reader) {
        Level level=reader instanceof Level l?l:reader instanceof ServerLevelAccessor access?access.getLevel():null;
        if(level instanceof ServerLevel serverLevel)return servers.getOrDefault(serverLevel.getServer(),Map.of()).get(level.dimension().location());
        return level!=null && level.isClientSide?clients.get(level.dimension().location()):null;
    }
    public static synchronized void publish(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        var policies=new HashMap<ResourceLocation,JEDTimeDefinition>();
        definitions.forEach((id,definition)->{var time=definition.time();if(time!=null)policies.put(id,time);});
        var previous=servers.getOrDefault(server,Map.of());var next=new IdentityHashMap<>(servers);next.put(server,Map.copyOf(policies));servers=Collections.unmodifiableMap(next);
        for(var level:server.getAllLevels())if(!Objects.equals(previous.get(level.dimension().location()),policies.get(level.dimension().location())))JEDWorldSync.changed(level);
    }
    public static void client(ResourceLocation id,JEDTimeDefinition policy) {
        var next=new HashMap<>(clients);if(policy==null)next.remove(id);else next.put(id,policy);clients=Map.copyOf(next);
    }
    public static void clearClient() {clients=Map.of();}
    public static float celestial(float nativeValue,LevelTimeAccess reader,float interpolation) {
        var policy=policy(reader);if(policy==null || !policy.customAngle())return nativeValue;
        float partial=0;
        if(reader instanceof Level level) {
            partial=level.getDayTimeFraction();
            if(level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT) && level.tickRateManager().runsNormally())
                partial+=interpolation*(level.getDayTimePerTick()<0?1:level.getDayTimePerTick());
        }
        return JEDTimeMath.angle(policy,reader.dayTime(),partial);
    }
    public static int moon(int nativeValue,LevelTimeAccess reader) {var policy=policy(reader);return policy==null?nativeValue:JEDTimeMath.moon(policy,reader.dayTime());}
    public static float moonBrightness(float nativeValue,LevelTimeAccess reader) {var policy=policy(reader);return policy==null?nativeValue:DimensionType.MOON_BRIGHTNESS_PER_PHASE[JEDTimeMath.moon(policy,reader.dayTime())];}
    public static boolean fixedTime(boolean nativeValue,Level level) {var policy=policy(level);return policy!=null && policy.customAngle()?false:nativeValue;}
    public static void serverAdvance(ServerLevel level,long proposed) {
        var policy=policy(level);long next=policy==null?proposed:JEDTimeMath.advance(policy,level.getDayTime(),proposed-level.getDayTime());level.setDayTime(next);
    }
    /** Replace the proposal before NeoForge posts its event, so other mods retain their event authority. */
    public static long sleep(ServerLevel level,long proposed,long current) {
        var policy=policy(level);long target=policy==null?proposed:JEDTimeMath.dawn(policy,current);
        return EventHooks.onSleepFinished(level,target,current);
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event) {
        var next=new IdentityHashMap<>(servers);next.remove(event.getServer());servers=Collections.unmodifiableMap(next);
    }
    private JEDTimeController() {}
}
