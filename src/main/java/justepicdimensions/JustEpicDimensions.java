package justepicdimensions;

import justepicdimensions.lighting.*;
import justepicdimensions.definition.JEDLoaderConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(JustEpicDimensions.ID)
public final class JustEpicDimensions {
    public static final String ID="justepicdimensions";
    public JustEpicDimensions(IEventBus bus,ModContainer container){
        justepicdimensions.item.JEDItems.register(bus);
        bus.addListener(justepicdimensions.item.JEDGuideBookOpen::register);
        justepicdimensions.worldgen.JEDGeneratorFactory.register(bus);
        justepicdimensions.travel.JEDTravelEntityArgument.register(bus);
        justepicdimensions.config.JEDConfigPaths.migrateGlobal();
        container.registerConfig(ModConfig.Type.CLIENT,JEDLightingConfig.SPEC,justepicdimensions.config.JEDConfigPaths.CLIENT_REGISTRATION);
        container.registerConfig(ModConfig.Type.COMMON,JEDLoaderConfig.SPEC,justepicdimensions.config.JEDConfigPaths.MAIN_REGISTRATION);
        bus.addListener(JEDLightPolicySync::register);
        bus.addListener(justepicdimensions.dimension.JEDDimensionSync::register);
        bus.addListener(justepicdimensions.dimension.JEDDimensionSync::configuring);
        bus.addListener(justepicdimensions.dimension.JEDDimensionNamesSync::register);
        bus.addListener(justepicdimensions.world.JEDWorldSync::register);
        bus.addListener(justepicdimensions.provider.JEDProviderConfiguration::register);
        bus.addListener(justepicdimensions.provider.JEDProviderConfiguration::tasks);
        bus.addListener(justepicdimensions.travel.JEDTravelSync::register);
        bus.addListener(justepicdimensions.builder.JEDBuilderWire::register);
        bus.addListener(justepicdimensions.config.JEDConfigWire::register);
        bus.addListener(JustEpicDimensions::setup);
        bus.addListener(justepicdimensions.notification.JEDNotificationCompliance::loadComplete);
    }
    /** A required Arbitrage change was not applied. Name both mods and where to look: NeoForge shows only this message. */
    public static IllegalStateException arbitrageMissing(String part,String where){return new IllegalStateException("Just Epic Dimensions needs Arbitrage's "+part+" changes ("+where+"), but they were not applied. Reinstall the Just Epic Dimensions JAR unchanged (it includes Arbitrage) and remove any separate arbitrage JAR from the mods folder. If that does not help, an earlier error from \"Arbitrage\" in the log names the cause.");}
    private static void setup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event){
        event.enqueueWork(()->{
            for(String type:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.server.level.ServerPlayer","net.minecraft.server.network.ServerGamePacketListenerImpl"}){
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.travel.coremod."+type)))throw arbitrageMissing("travel",type);
            }
            for(String type:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.world.level.Level","net.minecraft.server.MinecraftServer","net.minecraft.server.level.PlayerRespawnLogic","net.minecraft.server.level.ChunkMap","net.minecraft.world.level.levelgen.WorldDimensions","net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator","net.minecraft.world.level.chunk.status.ChunkStatusTasks"}){
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.worldgen.coremod."+type)))throw arbitrageMissing("worldgen",type);
            }
            for(String type:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.world.level.levelgen.feature.SpikeFeature","net.minecraft.world.level.dimension.end.DragonRespawnAnimation$3","net.minecraft.world.level.block.EndPortalBlock"}){
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.end.coremod."+type)))throw arbitrageMissing("end",type);
            }
            for(String type:new String[]{"net.minecraft.world.item.CompassItem","net.minecraft.world.level.block.NetherPortalBlock","net.minecraft.world.item.BucketItem","net.minecraft.world.level.block.IceBlock","net.neoforged.neoforge.fluids.FluidType","net.minecraft.world.level.saveddata.maps.MapItemSavedData"}){
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.provider.coremod."+type)))throw arbitrageMissing("provider",type);
            }
            for(String type:new String[]{"net.minecraft.server.players.PlayerList","net.minecraft.server.level.ServerPlayer"}) {
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.player.coremod."+type)))throw arbitrageMissing("player-state",type);
            }
            for(String type:new String[]{"net.minecraft.server.players.PlayerList","net.minecraft.server.level.ServerPlayer","net.minecraft.server.level.ServerLevel","net.minecraft.world.level.Level","net.minecraft.world.level.block.BedBlock","net.minecraft.network.Connection","net.minecraft.server.network.ServerCommonPacketListenerImpl","net.minecraft.server.network.ServerGamePacketListenerImpl"}) {
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.spawn.coremod."+type)))throw arbitrageMissing("spawn",type);
            }
            for(String type:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.server.level.ServerChunkCache"}) {
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.mob.coremod."+type)))throw arbitrageMissing("mob-spawn",type);
            }
            for(String type:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.world.level.biome.Biome"}) {
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.weather.coremod."+type)))throw arbitrageMissing("weather",type);
            }
            for(String type:new String[]{"net.minecraft.world.level.LevelTimeAccess","net.minecraft.world.level.Level","net.minecraft.server.level.ServerLevel"}) {
                try{Class.forName(type,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.time.coremod."+type)))throw arbitrageMissing("time",type);
            }
            try{
                Class.forName("net.minecraft.world.level.lighting.LevelLightEngine",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.world.level.LevelReader",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.world.level.lighting.LayerLightSectionStorage",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.WorldLoader",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.WorldLoader$PackConfig",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.packs.repository.ServerPacksSource",false,JustEpicDimensions.class.getClassLoader());
            }catch(ClassNotFoundException error){throw new IllegalStateException("JED native light classes are unavailable",error);}
            for(String component:new String[]{"engine","storage"})
                if(!"verified".equals(System.getProperty("jed.light.coremod."+component)))
                    throw arbitrageMissing("light",component);
            if(!"verified".equals(System.getProperty("jed.brightness.coremod.net.minecraft.world.level.LevelReader")))throw arbitrageMissing("brightness","net.minecraft.world.level.LevelReader");
            for(String component:new String[]{"startup","image","repository","reload"})
                if(!"verified".equals(System.getProperty("jed.definition.coremod."+component)))
                    throw arbitrageMissing("definition-loader",component);
            try {
                Class.forName("net.minecraft.server.level.ServerPlayer",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.players.PlayerList",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.world.level.chunk.PalettedContainer",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.world.level.levelgen.WorldDimensions",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.world.level.storage.LevelStorageSource",false,JustEpicDimensions.class.getClassLoader());
                Class.forName("net.minecraft.server.level.ChunkMap",false,JustEpicDimensions.class.getClassLoader());
            } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
            for(String component:new String[]{"registry","server","player","respawn","command","palette","bake","save_access","closed_save","shutdown_scope","unload_ownership","login","respawn_fallback"})
                if(!"verified".equals(System.getProperty("jed.dimension.coremod."+component)))
                    throw arbitrageMissing("runtime-dimension",component);
            for(String suffix:new String[]{"server.level.ServerLevel","server.MinecraftServer","server.players.PlayerList","server.level.ServerPlayer","server.network.ServerGamePacketListenerImpl","world.level.GameRules","world.level.GameRules$Value","server.ServerFunctionManager","server.commands.TimeCommand","server.commands.WeatherCommand","server.commands.WorldBorderCommand","server.commands.GameRuleCommand","server.commands.DifficultyCommand","server.commands.DefaultGameModeCommands","server.commands.ScheduleCommand","server.commands.SetWorldSpawnCommand"}) {
                String name="net.minecraft."+suffix;
                try{Class.forName(name,false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
                if(!"verified".equals(System.getProperty("jed.world.coremod."+name)))throw arbitrageMissing("world-control",name);
            }
            try{Class.forName("net.minecraft.world.level.chunk.LevelChunk",false,JustEpicDimensions.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}
            for(String name:new String[]{"net.minecraft.server.level.ServerLevel","net.minecraft.world.level.chunk.LevelChunk"})if(!"verified".equals(System.getProperty("jed.structure.coremod."+name)))throw arbitrageMissing("structure tick",name);
            for(String component:new String[]{"metadata","defaults"})if(!"verified".equals(System.getProperty("jed.template.coremod."+component)))throw arbitrageMissing("template",component);
            var loginClasses = justepicdimensions.player.JEDLoginClasses.prepare();
            var spawnChunkClasses = justepicdimensions.spawn.JEDSpawnChunkClasses.prepare();
            if(!"verified".equals(System.getProperty("arbitrage.render.coremod.net.minecraft.world.level.Level")))throw new IllegalStateException("Arbitrage required common rendering transform is missing");
            justepicdimensions.definition.JEDInfoLog.startup(
                "JED prepared {} login classes without initialization during startup in {} ms",
                loginClasses.classes(), loginClasses.elapsedNanos() / 1_000_000.0);
            justepicdimensions.definition.JEDInfoLog.startup(
                "JED prepared {} spawn-chunk classes without initialization during startup in {} ms",
                spawnChunkClasses.classes(), spawnChunkClasses.elapsedNanos() / 1_000_000.0);
        });
    }
}



