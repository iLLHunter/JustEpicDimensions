package justepicdimensions.structure;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.world.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.*;

/** Closed template imports precede native metadata/level construction. Existing saves are never replaced. */
public final class JEDTemplateManager {
    private static final Map<Path,CompoundTag> IMPORTED=new ConcurrentHashMap<>();
    private static final Map<WorldData,JEDLevelData> PRIMARY=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> PRIVATE=Set.of("playerdata","players","advancements","stats","session.lock","ops.json","whitelist.json","banned-players.json","banned-ips.json","usercache.json","justepicdimensions_players.dat","level.dat_old");
    public static boolean included(Path relative){for(Path part:relative){String name=part.toString().toLowerCase(Locale.ROOT);if(PRIVATE.contains(name)||name.startsWith(".jed-"))return false;}return true;}
    public static void validateClosed(Path template){
        JEDDimensionFiles.validateTemplate(template);
        try{if(Files.isRegularFile(template.resolve("session.lock"))&&net.minecraft.util.DirectoryLock.isLocked(template))throw new JEDDefinitionException("Template is in use; close its Minecraft world/server before importing: "+template);}catch(java.nio.channels.OverlappingFileLockException error){throw new JEDDefinitionException("Template is in use; close its Minecraft world/server before importing: "+template,error);}catch(IOException error){throw new JEDDefinitionException("Cannot verify template session lock "+template,error);}
        var lifecycle=JEDLifecycleStore.read(template);if(lifecycle.operation()!=null||!lifecycle.deferredCommands().isEmpty())throw new JEDDefinitionException("Template has unfinished JED lifecycle work; complete it in the source world before exporting: "+template);
        metadata(template);
    }
    public static void sanitizeFile(Path file) throws IOException {
        if(file.getFileName().toString().equals("level.dat")||file.getFileName().toString().equals("level.dat_old")){
            var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());var data=tag.getCompound("Data");data.remove("Player");NbtIo.writeCompressed(tag,file);
        }
    }
    /** Returns imported primary metadata only when this invocation installed a new Overworld. */
    public static CompoundTag initialize(JEDPreparedDefinitions prepared){
        Path world=prepared.selection().world();if(world==null)return null;
        CompoundTag primary=null;boolean newWorld=newPrimary(world);
        for(var entry:prepared.resources().entrySet())if(entry.getKey().getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),JEDDimensionDefinition.DIRECTORY);var definition=JEDDimensionDefinition.metadata(id,entry.getValue());String name=definition.lifecycle().template();if(name==null||prepared.disabled().contains(id))continue;
            Path template=prepared.selection().source().resolve("templates").resolve(name).toAbsolutePath().normalize();JEDDimensionFiles.requireSafe(prepared.selection().source().resolve("templates"),template,false);
            if(id.equals(Level.OVERWORLD.location())){if(newWorld)primary=primary(world,template);}
            else JEDDimensionFiles.initializeTemplate(world,id,template);
        }
        if(primary!=null)IMPORTED.put(world.toAbsolutePath().normalize(),primary.copy());
        return primary;
    }
    private static CompoundTag primary(Path world,Path template){
        validateClosed(template);CompoundTag metadata=metadata(template);
        if(metadata==null)throw new JEDDefinitionException("Overworld template requires a 1.21.1 level.dat: "+template);
        Path stage=world.resolve(".jed-template-staging/primary"),intent=world.resolve(".jed-template-import.json");JEDDimensionFiles.requireSafe(world,stage,false);
        try{
            if(Files.exists(stage))JEDDimensionFiles.deleteTree(world.resolve(".jed-template-staging"),stage);
            JEDDimensionFiles.copyTree(template,stage);
            var record=new JsonObject();record.addProperty("template",template.toString());record.addProperty("phase","STAGED");JEDDimensionFiles.writeDurable(intent,record);
            Files.writeString(world.resolve(".jed-template-new"),template.toString(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            // level.dat is installed last. A failed/interrupted import remains a new save and can be retried.
            try(var paths=Files.walk(stage)){for(var path:paths.sorted().toList()){
                Path relative=stage.relativize(path);if(relative.toString().isEmpty()||relative.toString().equals("level.dat"))continue;Path target=world.resolve(relative);JEDDimensionFiles.requireSafe(world,target,false);
                if(Files.isDirectory(path))Files.createDirectories(target);else if(!Files.exists(target)){Files.createDirectories(target.getParent());Files.move(path,target);}
            }}
            Files.move(stage.resolve("level.dat"),world.resolve("level.dat"));Files.deleteIfExists(intent);JEDDimensionFiles.deleteTree(world.resolve(".jed-template-staging"),stage);
            JEDInfoLog.info(JEDConfigControls.read(JEDConfigRepository.select(world)),"JED imported new Overworld from {}",template);return metadata;
        }catch(IOException error){throw new JEDDefinitionException("Cannot import Overworld template "+template+"; existing files were retained",error);}
    }
    private static boolean newPrimary(Path world){
        if(Files.exists(world.resolve("level.dat"))||Files.exists(world.resolve("level.dat_old")))return false;
        if(Files.exists(world.resolve(".jed-template-import.json")))return true;
        for(String name:List.of("region","entities","poi","playerdata","DIM-1","DIM1","dimensions"))if(Files.exists(world.resolve(name)))return false;
        return true;
    }
    public static CompoundTag metadata(Path template){
        Path file=template.resolve("level.dat");if(!Files.isRegularFile(file))return null;JEDDimensionFiles.requireSafe(template,file,false);
        try{
            var root=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());if(!root.contains("Data",Tag.TAG_COMPOUND))throw new JEDDefinitionException("Template level.dat has no Data compound: "+file);
            var data=root.getCompound("Data");int expected=SharedConstants.getCurrentVersion().getDataVersion().getVersion();if(data.getInt("DataVersion")!=expected)throw new JEDDefinitionException("Template "+file+" requires Minecraft 1.21.1 data version "+expected+"; legacy-world migration is unsupported");
            data.remove("Player");if(!data.contains("neoDayTimePerTick"))data.putFloat("neoDayTimePerTick",1);return data;
        }catch(IOException error){throw new JEDDefinitionException("Cannot read template metadata "+file,error);}
    }
    /** Java-coremod bridge at the shared supplier boundary, including single-player's fresh-world path. */
    @SuppressWarnings("unchecked") public static <D> WorldLoader.DataLoadOutput<D> worldData(WorldLoader.WorldDataSupplier<D> supplier,WorldLoader.DataLoadContext context){
        JEDConfiguredDimensions.bind(context);
        // World data records its generation-settings lifecycle while it is decoded, so mark JED's own entries first.
        if(context.resources() instanceof JEDDefinitionResources resolved)justepicdimensions.definition.JEDRegistryStability.worldgen(context.datapackWorldgen(),resolved.prepared());
        var original=supplier.get(context);
        if(!(original.cookie() instanceof WorldData)||!(context.resources() instanceof JEDDefinitionResources resources))return original;
        Path world=resources.prepared().selection().world();var data=world==null?null:IMPORTED.remove(world.toAbsolutePath().normalize());
        if(data!=null){var decoded=LevelStorageSource.getLevelDataAndDimensions(new Dynamic<>(NbtOps.INSTANCE,data),context.dataConfiguration(),context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM),context.datapackWorldgen());original=new WorldLoader.DataLoadOutput<>((D)decoded.worldData(),decoded.dimensions().dimensionsRegistryAccess());}
        var key=ResourceLocation.withDefaultNamespace(JEDDimensionDefinition.DIRECTORY+"/overworld.json");var source=resources.prepared().resources().get(key);
        if(source!=null&&JEDDimensionDefinition.metadata(Level.OVERWORLD.location(),source).lifecycle().template()!=null){var primary=(WorldData)original.cookie();PRIMARY.put(primary,JEDLevelData.copyOf(primary.overworldData()));}
        return original;
    }
    public static JEDLevelData primaryDefaults(ServerLevelData primary){var state=PRIMARY.get(primary);return state==null?JEDLevelData.copyOf(primary):JEDLevelData.copyOf(state);}
    public static void primaryApplied(net.minecraft.server.level.ServerLevel level){if(level.dimension()==Level.OVERWORLD&&PRIMARY.containsKey(level.getServer().getWorldData()))PRIMARY.put(level.getServer().getWorldData(),JEDLevelData.copyOf((ServerLevelData)level.getLevelData()));}
    public static CompoundTag takeImported(net.minecraft.server.packs.resources.ResourceManager manager){if(!(manager instanceof JEDDefinitionResources resources)||resources.prepared().selection().world()==null)return null;return IMPORTED.remove(resources.prepared().selection().world().toAbsolutePath().normalize());}
    public static void restorePrimary(net.minecraft.server.MinecraftServer server){var selected=PRIMARY.get(server.getWorldData());if(selected!=null){server.getWorldData().setGameType(selected.getGameType());((PrimaryLevelData)server.getWorldData().overworldData()).setDifficulty(selected.getDifficulty());}}
    public static JEDLevelData dimensionState(Path world,ResourceLocation id,ServerLevelData defaults){
        var folder=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION,id),world);var tag=metadata(folder);
        if(tag!=null)return JEDLevelData.read(tag);
        var data=JEDLevelData.copyOf(defaults);data.clearScheduledEvents();return data;
    }
    public static WorldOptions generation(Path world,ResourceLocation id){
        if(id.equals(Level.OVERWORLD.location()))return null;var folder=JEDDimensionFiles.directory(world,id);var tag=metadata(folder);if(tag==null||!tag.contains("WorldGenSettings",Tag.TAG_COMPOUND))return null;
        return WorldOptions.CODEC.codec().parse(NbtOps.INSTANCE,tag.getCompound("WorldGenSettings")).getOrThrow();
    }
    public static void rebase(Path folder,ResourceLocation id) throws IOException {
        Path data=folder.resolve("data/justepicdimensions");if(Files.isDirectory(data))try(var files=Files.list(data)){for(var file:files.filter(p->p.toString().endsWith(".dat")).toList()){JEDDimensionFiles.requireSafe(folder,file,false);var tag=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());if(tag.contains("dimension",Tag.TAG_STRING)&&tag.getInt("format_version")==1){tag.putString("dimension",id.toString());NbtIo.writeCompressed(tag,file);}}}
    }
    private JEDTemplateManager(){}
}
