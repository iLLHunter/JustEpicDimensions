package justepicdimensions.client;

import java.util.*;
import com.mojang.serialization.Dynamic;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.world.JEDWorldSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDClientWorldRuntime {
    private static final Map<ResourceLocation,CompoundTag> CACHE=new HashMap<>();
    public static void receive(JEDWorldSync.Snapshot snapshot,net.neoforged.neoforge.network.handling.IPayloadContext context){
        // Pre-login snapshots precede Minecraft.level; their play listener already owns the registries.
        var minecraft=Minecraft.getInstance();var listener=(net.minecraft.client.multiplayer.ClientPacketListener)context.listener();var provider=justepicdimensions.provider.JEDProviderController.prepareClient(snapshot.dimension(),snapshot.data(),net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,listener.registryAccess()));
        JEDClientRenderRuntime.receive(snapshot.dimension(),snapshot.data().contains("jed_render_policy")?justepicdimensions.render.JEDRenderDefinition.decode(com.google.gson.JsonParser.parseString(snapshot.data().getString("jed_render_policy")),snapshot.dimension()+".render"):null);
        justepicdimensions.world.JEDBorderController.client(snapshot.dimension(),snapshot.data().getBoolean("jed_local_border"));
        justepicdimensions.provider.JEDProviderController.client(snapshot.dimension(),provider,minecraft.level);
        justepicdimensions.time.JEDTimeController.client(snapshot.dimension(),snapshot.data().contains("jed_time_policy")?justepicdimensions.time.JEDTimeDefinition.decode(com.google.gson.JsonParser.parseString(snapshot.data().getString("jed_time_policy")),snapshot.dimension()+".time"):null);
        CACHE.put(snapshot.dimension(),snapshot.data());var level=Minecraft.getInstance().level;if(level!=null&&level.dimension().location().equals(snapshot.dimension()))apply(level,snapshot.data());}
    public static ClientLevel.ClientLevelData construct(ClientLevel.ClientLevelData original,ResourceKey<Level> dimension){var tag=CACHE.get(dimension.location());if(tag==null)return original;
        var data=new ClientLevel.ClientLevelData(Difficulty.byId(tag.getByte("Difficulty")),tag.getBoolean("hardcore"),original.isFlat);applyData(data,tag);return data;
    }
    private static void applyData(ClientLevel.ClientLevelData data,CompoundTag tag){
        data.setDifficulty(Difficulty.byId(tag.getByte("Difficulty")));data.setDifficultyLocked(tag.getBoolean("DifficultyLocked"));data.setSpawn(new BlockPos(tag.getInt("SpawnX"),tag.getInt("SpawnY"),tag.getInt("SpawnZ")),tag.getFloat("SpawnAngle"));
        data.setGameTime(tag.getLong("Time"));data.setDayTime(tag.getLong("DayTime"));data.setRaining(tag.getBoolean("raining"));
        data.getGameRules().assignFrom(new GameRules(new Dynamic<>(NbtOps.INSTANCE,tag.getCompound("GameRules"))),null);
    }
    public static void bind(ClientLevel level){var tag=CACHE.get(level.dimension().location());if(tag!=null)apply(level,tag);justepicdimensions.provider.JEDProviderController.bindClient(level);JEDClientRenderRuntime.bind(level);}
    private static void apply(ClientLevel level,CompoundTag tag){
        applyData(level.getLevelData(),tag);level.setDayTimeFraction(tag.getFloat("neoDayTimeFraction"));level.setDayTimePerTick(tag.getFloat("neoDayTimePerTick"));level.setRainLevel(tag.getFloat("rain_level"));level.setThunderLevel(tag.getFloat("thunder_level"));
        var minecraft=Minecraft.getInstance();var rules=level.getGameRules();
        if(minecraft.player!=null){minecraft.player.setReducedDebugInfo(rules.getBoolean(GameRules.RULE_REDUCEDDEBUGINFO));minecraft.player.setShowDeathScreen(!rules.getBoolean(GameRules.RULE_DO_IMMEDIATE_RESPAWN));minecraft.player.setDoLimitedCrafting(rules.getBoolean(GameRules.RULE_LIMITED_CRAFTING));}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){CACHE.clear();justepicdimensions.time.JEDTimeController.clearClient();justepicdimensions.world.JEDBorderController.clearClient();justepicdimensions.provider.JEDProviderController.clearClient();JEDClientRenderRuntime.clear();}
    @EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void providerSetup(FMLClientSetupEvent event){event.enqueueWork(()->{for(String type:List.of("net.minecraft.client.renderer.item.ItemProperties$1","net.minecraft.client.multiplayer.ClientLevel$ClientLevelData","net.minecraft.client.renderer.LevelRenderer")){
            try{Class.forName(type,false,JEDClientWorldRuntime.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
            if(!"verified".equals(System.getProperty("jed.provider.coremod."+type)))throw justepicdimensions.JustEpicDimensions.arbitrageMissing("client provider",type);
        }});}
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{JEDWorldSync.receiver(JEDClientWorldRuntime::receive);for(String type:List.of("net.minecraft.client.multiplayer.ClientLevel")){
            try{Class.forName(type,false,JEDClientWorldRuntime.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
            if(!"verified".equals(System.getProperty("jed.world.coremod."+type)))throw justepicdimensions.JustEpicDimensions.arbitrageMissing("world-control",type);
        }for(String type:List.of("net.minecraft.client.multiplayer.ClientLevel","net.neoforged.neoforge.network.handlers.ClientPayloadHandler")) {
            try{Class.forName(type,false,JEDClientWorldRuntime.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
            if(!"verified".equals(System.getProperty("jed.time.coremod."+type)))throw justepicdimensions.JustEpicDimensions.arbitrageMissing("time",type);
        }});}
    }
    private JEDClientWorldRuntime(){}
}
