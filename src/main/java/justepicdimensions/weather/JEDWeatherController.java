package justepicdimensions.weather;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.JEDDimensionDefinition;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Immutable per-server dispatch, also safe for native world-generation workers. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDWeatherController {
    public record Policy(JEDWeatherDefinition settings,boolean endProfile) {}
    private static volatile Map<MinecraftServer,Map<ResourceLocation,Policy>> servers=Map.of();
    public static Policy policy(LevelReader reader) {
        var level=reader instanceof ServerLevel l?l:reader instanceof ServerLevelAccessor access?access.getLevel():null;
        return level==null?null:servers.getOrDefault(level.getServer(),Map.of()).get(level.dimension().location());
    }
    public static synchronized void publish(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        var policies=new HashMap<ResourceLocation,Policy>();
        definitions.forEach((id,definition)->{
            var settings=definition.weather();boolean end=definition.provider()!=null&&definition.provider().type().profile()==Profile.END;
            if(settings!=null||end)policies.put(id,new Policy(settings==null?new JEDWeatherDefinition(null,null,null,null,false):settings,end));
        });
        var next=new IdentityHashMap<>(servers);next.put(server,Map.copyOf(policies));servers=Collections.unmodifiableMap(next);
    }
    private static boolean allowed(Boolean explicit,Policy policy){return explicit!=null?explicit:!policy.endProfile;}
    public static boolean lightning(boolean nativeValue,ServerLevel level) {
        if(!nativeValue)return false;var policy=policy(level);
        return policy==null||allowed(policy.settings.naturalLightning(),policy);
    }
    /** Only the native sleep call is adapted; direct reset calls and commands retain their meaning. */
    public static void resetAfterSleep(ServerLevel level) {
        var policy=policy(level);if(policy==null||!policy.settings.preserveOnSleep())level.resetWeatherCycle();
    }
    public static boolean freezeTick(Biome biome,LevelReader level,BlockPos pos) {
        var policy=policy(level);
        return (policy==null||policy.settings.ice()!=null||!policy.endProfile)&&biome.shouldFreeze(level,pos);
    }
    public static boolean snowTick(Biome biome,LevelReader level,BlockPos pos) {
        var policy=policy(level);
        return (policy==null||policy.settings.snow()!=null||!policy.endProfile)&&biome.shouldSnow(level,pos);
    }
    public static Biome.Precipitation precipitation(Biome.Precipitation nativeValue,ServerLevel level) {
        var policy=policy(level);
        return policy==null||allowed(policy.settings.precipitationBlockEffects(),policy)?nativeValue:Biome.Precipitation.NONE;
    }
    /** Change only the temperature decision; Minecraft still owns light, height, fluid and placement checks. */
    public static boolean warmEnough(boolean nativeValue,LevelReader level,boolean snow) {
        var policy=policy(level);if(policy==null)return nativeValue;
        var mode=snow?policy.settings.snow():policy.settings.ice();
        return mode==null||mode==JEDWeatherDefinition.Mode.NATIVE?nativeValue:mode==JEDWeatherDefinition.Mode.DISABLED;
    }
    @SubscribeEvent public static synchronized void stopped(ServerStoppedEvent event) {
        var next=new IdentityHashMap<>(servers);next.remove(event.getServer());servers=Collections.unmodifiableMap(next);
    }
    private JEDWeatherController() {}
}
