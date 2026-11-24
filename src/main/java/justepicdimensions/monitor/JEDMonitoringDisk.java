package justepicdimensions.monitor;
import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;

/** Detached logical file sizes; never follows links or counts nested dimensions twice. */
public final class JEDMonitoringDisk {
 public record Reading(long live,long history,long measured){public JsonObject json(){var j=new JsonObject();j.addProperty("disk_live_bytes",live);j.addProperty("disk_history_bytes",history);j.addProperty("disk_total_bytes",Math.addExact(live,history));j.addProperty("disk_measured_at",measured);return j;}}
 public static Reading measure(Path world,ResourceLocation id,Collection<ResourceLocation> dimensions)throws IOException{
  Path dir=directory(world,id);var excluded=new HashSet<Path>();for(var other:dimensions)if(!other.equals(id)){Path nested=directory(world,other);if(nested.startsWith(dir)&&!nested.equals(dir))excluded.add(nested);}
  long live=0;if(id.toString().equals("minecraft:overworld")){for(String name:List.of("region","entities","poi"))live=Math.addExact(live,size(world.resolve(name),Set.of()));}else live=size(dir,excluded);
  long history=0;Path trash=world.resolve(".jed-trash");JEDDimensionFiles.requireSafe(world,trash,false);if(Files.isDirectory(trash))try(var folders=Files.list(trash)){for(var folder:folders.toList()){if(!folder.getFileName().toString().matches("[a-f0-9-]{36}"))continue;JEDDimensionFiles.requireSafe(trash,folder,false);Path journal=folder.resolve("entry.json");JEDDimensionFiles.requireSafe(trash,journal,false);if(!Files.isRegularFile(journal))continue;var j=JsonParser.parseString(Files.readString(journal)).getAsJsonObject();if(j.has("dimension")&&j.get("dimension").getAsString().equals(id.toString()))history=Math.addExact(history,size(folder,Set.of()));}}
  return new Reading(live,history,System.currentTimeMillis());
 }
 private static Path directory(Path world,ResourceLocation id){Path p=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,id),world).toAbsolutePath().normalize();JEDDimensionFiles.requireSafe(world,p,true);return p;}
 public static long size(Path root,Set<Path> excluded)throws IOException{if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return 0;JEDDimensionFiles.requireSafe(root.getParent(),root,false);final long[] total={0};Files.walkFileTree(root,new SimpleFileVisitor<>(){public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a)throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Disk scan stopped");JEDDimensionFiles.requireSafe(root,p,true);return excluded.contains(p.toAbsolutePath().normalize())?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;}public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException{JEDDimensionFiles.requireSafe(root,p,true);if(!a.isRegularFile())throw new IOException("Unsupported monitoring disk entry "+p);total[0]=Math.addExact(total[0],a.size());return FileVisitResult.CONTINUE;}});return total[0];}
 private JEDMonitoringDisk(){}
}
