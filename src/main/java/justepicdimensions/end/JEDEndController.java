package justepicdimensions.end;

import com.mojang.logging.LogUtils;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import justepicdimensions.world.JEDDimensionStore;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.dimension.end.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Native fights with dimension-owned persistence, without a synthetic second fight implementation. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDEndController {
    private record FightPolicy(boolean managed,boolean enabled,boolean endProvider) {}
    private static final Map<ServerLevel,FightPolicy> ACTIVE=new WeakHashMap<>();
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDEndDefinition>> POLICIES=Map.of();
    private static FightPolicy policy(JEDDimensionDefinition definition) {
        boolean end=definition!=null&&definition.provider()!=null&&definition.provider().type().profile()==Profile.END;
        var setting=definition==null?JEDEndDefinition.DEFAULT:definition.end();
        return new FightPolicy(end||setting.dragonFight()!=null,setting.dragonFight()==null?end:setting.dragonFight(),end);
    }
    private static FightPolicy requested(ServerLevel level) {return policy(JEDDefinitionManager.state(level.getServer()).definitions().get(level.dimension().location()));}
    public static ResourceKey<Level> nativeEndKey(ServerLevel level) {return requested(level).managed()?null:Level.END;}
    public static void bind(ServerLevel level) {
        var policy=requested(level);ACTIVE.put(level,policy);if(!policy.managed()||!policy.enabled())return;
        var tag=level.dimension()==Level.END?null:JEDDimensionStore.read(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location(),"end_fight");
        var data=tag==null?(level.dimension()==Level.END?level.getServer().getWorldData().endDragonFightData():EndDragonFight.Data.DEFAULT):EndDragonFight.Data.CODEC.parse(NbtOps.INSTANCE,tag).getOrThrow(message->new JEDDefinitionException("Invalid saved End fight for "+level.dimension().location()+": "+message));
        level.dragonFight=new EndDragonFight(level,level.getSeed(),data);
    }
    public static void save(WorldData data,EndDragonFight.Data fight,ServerLevel level) {
        // The real End retains its native metadata owner, including when JED controls its fight.
        if(level.dimension()==Level.END){data.setEndDragonFightData(fight);return;}
        var policy=ACTIVE.get(level);if(policy==null||!policy.managed()){data.setEndDragonFightData(fight);return;}
        var tag=(CompoundTag)EndDragonFight.Data.CODEC.encodeStart(NbtOps.INSTANCE,fight).getOrThrow();
        JEDDimensionStore.save(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location(),"end_fight",tag);
    }
    public static ResourceKey<Level> endDimension(ResourceKey<Level> nativeKey,Level input) {
        if(!(input instanceof ServerLevel level))return nativeKey;
        var policy=ACTIVE.get(level);return policy!=null&&policy.endProvider()?Level.END:nativeKey;
    }
    public static boolean generateSpikes(WorldGenLevel level) {
        if(!(level instanceof WorldGenRegion))return true;
        var native_=level.getLevel();
        return POLICIES.getOrDefault(native_.getServer(),Map.of()).getOrDefault(native_.dimension().location(),JEDEndDefinition.DEFAULT).generateSpikes();
    }
    public static boolean skipReconstruction(ServerLevel level,EndDragonFight fight) {
        var policy=POLICIES.getOrDefault(level.getServer(),Map.of()).getOrDefault(level.dimension().location(),JEDEndDefinition.DEFAULT);
        if(policy.reconstructSpikes())return false;
        fight.setRespawnStage(DragonRespawnAnimation.SUMMONING_DRAGON);return true;
    }
    public static void publish(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions) {
        var entries=new HashMap<ResourceLocation,JEDEndDefinition>();definitions.forEach((id,d)->entries.put(id,d.end()));var next=new IdentityHashMap<>(POLICIES);next.put(server,Map.copyOf(entries));POLICIES=Collections.unmodifiableMap(next);
        for(var level:server.getAllLevels()) {var old=ACTIVE.get(level);if(old!=null&&!old.equals(policy(definitions.get(level.dimension().location()))))
            LogUtils.getLogger().warn("JED {} End fight/provider change is pending until the next safe dimension load; the active fight and its saved progress remain intact.",level.dimension().location());}
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)ACTIVE.remove(level);}
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){var next=new IdentityHashMap<>(POLICIES);next.remove(event.getServer());POLICIES=Collections.unmodifiableMap(next);}
    private JEDEndController(){}
}
