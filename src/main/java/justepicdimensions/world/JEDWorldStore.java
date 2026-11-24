package justepicdimensions.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.function.Consumer;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.ServerLevelData;

/** Versioned dimension-owned persistence, used by native construction and save bridges. */
public final class JEDWorldStore {
    public record Loaded(JEDLevelData data,boolean firstCreation) {}
    public static Path file(Path world,ResourceLocation dimension) {
        justepicdimensions.definition.JEDDimensionDefinition.id(dimension.toString(),"JED level-state dimension");
        var folder=DimensionType.getStorageFolder(ResourceKey.create(Registries.DIMENSION,dimension),world);
        var file=folder.resolve("data/justepicdimensions/level_state.dat").toAbsolutePath().normalize();JEDDimensionFiles.requireSafe(world,file,false);return file;
    }
    public static Loaded open(Path world,ResourceLocation dimension,ServerLevelData defaults,Consumer<JEDLevelData> onLoad,Consumer<JEDLevelData> onFirstCreation) {
        var file=file(world,dimension);boolean fresh=!Files.exists(file);JEDLevelData data;
        if(fresh)data=justepicdimensions.structure.JEDTemplateManager.dimensionState(world,dimension,defaults);
        else try{
            var root=NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap());
            if(root.getInt("format_version")!=1||!root.getString("dimension").equals(dimension.toString())||!root.contains("state",Tag.TAG_COMPOUND))throw new JEDDefinitionException("Incompatible JED level-state file: "+file);
            data=JEDLevelData.read(root.getCompound("state"));
        }catch(IOException error){throw new JEDDefinitionException("Cannot read JED level-state file: "+file,error);}
        onLoad.accept(data);if(fresh)onFirstCreation.accept(data);return new Loaded(data,fresh);
    }
    public static void save(Path world,ResourceLocation dimension,JEDLevelData data) {
        var file=file(world,dimension);Path temporary=null;
        try{
            Files.createDirectories(file.getParent());temporary=Files.createTempFile(file.getParent(),".jed-state-",".tmp");
            var root=new CompoundTag();root.putInt("format_version",1);root.putString("dimension",dimension.toString());root.put("state",data.save());NbtIo.writeCompressed(root,temporary);
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException error){throw new JEDDefinitionException("Cannot save JED level state: "+file,error);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    private JEDWorldStore() {}
}
