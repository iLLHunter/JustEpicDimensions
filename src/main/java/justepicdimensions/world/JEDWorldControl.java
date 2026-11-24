package justepicdimensions.world;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.border.*;
import net.minecraft.world.level.storage.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDWorldControl {
    private static final Map<MinecraftServer,JEDWorldOptions> OPTIONS=new IdentityHashMap<>();
    public static JEDWorldOptions options(MinecraftServer server){return OPTIONS.getOrDefault(server,new JEDWorldOptions(true,false,true));}
    public static void validate(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var next=candidate.worldOptions();var old=OPTIONS.get(server);
        for(var definition:definitions.values())if(definition.time()!=null && definition.time().clockRange()!=null && !definition.id().equals(Level.OVERWORLD.location()) && (!next.independent() || definition.worldState()==null))throw new JEDDefinitionException(definition.id()+": time.clock_range requires independent state; configure state and enableSeparateWorldInfo");
        if(old!=null && (old.independent()!=next.independent()||old.borders()!=next.borders()))throw new JEDDefinitionException("World-control construction switches require reopening the world");
        if(old!=null)for(var level:server.getAllLevels()){
            var context=context(level);var definition=definitions.get(level.dimension().location());
            boolean authored=next.independent() && definition!=null && (definition.worldState()!=null||definition.lifecycle().template()!=null);
            if(context!=null && authored!=context.authored())throw new JEDDefinitionException(level.dimension().location()+": adding/removing state requires unloading this dimension first (Overworld: reopen the world)");
        }
    }
    public static void commit(MinecraftServer server,JEDPreparedDefinitions candidate){OPTIONS.put(server,candidate.worldOptions());}
    public static JEDLevelContext context(ServerLevel level){return ((JEDLevelAccess)(Object)level).jed$context();}
    public static ServerLevelData construct(MinecraftServer server,ServerLevelData original,ResourceKey<Level> key){
        justepicdimensions.structure.JEDStructurePlacer.construct(server,key);
        var definition=JEDDefinitionManager.state(server).definitions().get(key.location());
        var authored=options(server).independent() && definition!=null?definition.worldState():null;
        if(authored==null&&options(server).independent()&&definition!=null&&definition.lifecycle().template()!=null)authored=JEDStateDefinition.decode(new com.google.gson.JsonObject(),key.location()+".template_state");
        if(authored==null)return original;
        var world=server.getWorldPath(LevelResource.ROOT);
        if(key==Level.OVERWORLD){
            // Native primary data remains the authoritative owner for the save and shared vanilla levels.
            var fresh=!java.nio.file.Files.exists(JEDWorldStore.file(world,key.location()));
            var patch=justepicdimensions.structure.JEDTemplateManager.primaryDefaults(original);authored.onLoad().apply(patch);if(fresh)authored.initial().apply(patch);
            var primary=(PrimaryLevelData)original;var settings=primary.getLevelSettings();
            original.getGameRules().assignFrom(patch.getGameRules(),null);
            primary.settings=new LevelSettings(patch.getLevelName(),patch.getGameType(),patch.isHardcore(),patch.getDifficulty(),patch.isAllowCommands(),original.getGameRules(),settings.getDataConfiguration(),settings.getLifecycle());
            original.setSpawn(patch.getSpawnPos(),patch.getSpawnAngle());original.setGameTime(patch.getGameTime());original.setDayTime(patch.getDayTime());original.setDayTimeFraction(patch.getDayTimeFraction());original.setDayTimePerTick(patch.getDayTimePerTick());
            primary.setDifficultyLocked(patch.isDifficultyLocked());original.setInitialized(patch.isInitialized());original.setRaining(patch.isRaining());original.setThundering(patch.isThundering());original.setClearWeatherTime(patch.getClearWeatherTime());original.setRainTime(patch.getRainTime());original.setThunderTime(patch.getThunderTime());original.setWorldBorder(patch.getWorldBorder());
            return original;
        }
        // Only fresh queues are cleared. The store applies regular overrides before initial overrides.
        var selected=authored;
        var loaded=JEDWorldStore.open(world,key.location(),original,data->selected.onLoad().apply(data),data->{if(definition.lifecycle().template()==null)data.clearScheduledEvents();selected.initial().apply(data);});
        return loaded.data();
    }
    public static boolean tickTime(boolean nativeValue,ServerLevelData data){return nativeValue||data instanceof JEDLevelData;}
    public static void constructionMode(ServerLevelData data,GameType nativeMode){if(!(data instanceof JEDLevelData))data.setGameType(nativeMode);}
    public static GameType defaultMode(GameType nativeMode,net.minecraft.server.level.ServerPlayer player){return player.level().getLevelData() instanceof JEDLevelData data?data.getGameType():nativeMode;}
    public static GameType forcedMode(GameType nativeMode,net.minecraft.server.level.ServerPlayer player){if(nativeMode==null)return null;if(player.server.overworld()!=null&&justepicdimensions.spawn.JEDPlayerStateTracker.get(player.server).player(player.getUUID()).hardcoreDead)return null;return defaultMode(nativeMode,player);}
    public static boolean hardcoreDeath(net.minecraft.server.level.ServerPlayer player){
        var level=player.serverLevel();var context=context(level);
        if(context!=null&&context.authored()){
            var definition=JEDDefinitionManager.state(player.server).definitions().get(level.dimension().location());
            if(definition!=null&&(definition.worldState()==null||definition.worldState().followDimensionHardcoreRules()))return level.getLevelData().isHardcore();
        }return player.server.isHardcore();
    }
    public static void bind(ServerLevel level){
        justepicdimensions.provider.JEDProviderController.bind(level);
        justepicdimensions.structure.JEDTemplateManager.primaryApplied(level);
        var definition=JEDDefinitionManager.state(level.getServer()).definitions().get(level.dimension().location());var data=(ServerLevelData)level.getLevelData();var options=options(level.getServer());
        var context=new JEDLevelContext(level,data,data instanceof JEDLevelData,options.independent()&&definition!=null&&(definition.worldState()!=null||definition.lifecycle().template()!=null),options);
        ((JEDLevelAccess)(Object)level).jed$context(context);JEDGameruleCallbacks.bind(level);
        spawnSettings(level,level.getServer().isSpawningMonsters(),level.getServer().isSpawningAnimals());
        if(options.borders()) {
            var border=data.getWorldBorder();
            if(!context.authored()&&java.nio.file.Files.exists(JEDWorldStore.file(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location())))
                border=JEDWorldStore.open(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location(),data,ignored->{},ignored->{}).data().getWorldBorder();
            level.getWorldBorder().applySettings(border);JEDWorldBorders.listen(level);
        }
    }
    public static void save(ServerLevel level){var context=context(level);if(context!=null&&(context.authored()||context.options().borders())){
        var data=JEDLevelData.copyOf(context.data());data.setWorldBorder(level.getWorldBorder().createSettings());JEDWorldStore.save(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location(),data);
    }}
    public static void spawnSettings(ServerLevel level,boolean monsters,boolean animals){
        if(level.getLevelData() instanceof JEDLevelData){monsters=level.getDifficulty()!=Difficulty.PEACEFUL;
            if(level.getServer() instanceof net.minecraft.server.dedicated.DedicatedServer dedicated)monsters &= dedicated.getProperties().spawnMonsters;
        }level.setSpawnSettings(monsters,animals);
    }
    public static void delegate(WorldBorder border,BorderChangeListener listener,MinecraftServer server){if(!options(server).borders())border.addListener(listener);}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)JEDGameruleCallbacks.unbind(level);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){OPTIONS.remove(event.getServer());JEDGameruleCallbacks.stopped(event.getServer());}
    private JEDWorldControl(){}
}
