package justepicdimensions.admin;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.JEDBuilderFiles;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native state is copied on ticks; formatting and export IO consume detached JSON on one worker. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDAdminReports {
    public record Export(Path directory,int dimensions,int slices,long maximumCaptureSliceNanos){}
    private static final Map<MinecraftServer,Job> JOBS=new IdentityHashMap<>();
    public static boolean busy(MinecraftServer server){return JOBS.containsKey(server);}
    public static CompletableFuture<Export> pending(MinecraftServer server){var job=JOBS.get(server);if(job==null)throw new JEDDefinitionException("No administrative report is running");return job.result;}
    public static CompletableFuture<Export> export(CommandSourceStack source,ResourceLocation dimension){
        var server=source.getServer();JEDAdminSnapshot.thread(server);if(!source.hasPermission(2))throw new JEDDefinitionException("Administrative reports require operator level 2");if(busy(server))throw new JEDDefinitionException("An administrative report is already preparing");
        var job=new Job(server,dimension);JOBS.put(server,job);return job.result;
    }
    private static final class Job {
        final MinecraftServer server;final JEDAdminSnapshot.Capture capture;final Path root;final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED administrative export").factory());
        final CompletableFuture<Export> result=new CompletableFuture<>();boolean writing;volatile boolean stopped;int slices;long maximum;
        Job(MinecraftServer server,ResourceLocation dimension){this.server=server;root=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().resolve("jed-reports");capture=new JEDAdminSnapshot.Capture(server,dimension);}
        void tick(){if(writing)return;long start=System.nanoTime();try{
            boolean more=true;int count=0;while(more&&count++<8&&System.nanoTime()-start<2_000_000L)more=capture.advance();slices++;maximum=Math.max(maximum,System.nanoTime()-start);if(more)return;
            var image=capture.finish();writing=true;CompletableFuture.supplyAsync(()->{Path directory;synchronized(this){if(stopped)throw new CancellationException("Report stopped");directory=directory(root);}if(stopped)throw new CancellationException("Report stopped");return writeFiles(directory,image,slices,maximum);},worker).whenComplete((out,error)->{if(!stopped)server.execute(()->{if(JOBS.get(server)!=this)return;if(error!=null)fail(error);else{JOBS.remove(server,this);worker.shutdown();result.complete(out);}});});
        }catch(Throwable e){fail(e);}}
        void fail(Throwable e){JOBS.remove(server,this);worker.shutdown();result.completeExceptionally(e);}
    }
    public static Export write(Path root,JEDAdminSnapshot.Image image,int slices,long maximum){
        return writeFiles(directory(root),image,slices,maximum);
    }
    private static Path directory(Path root){var directory=JEDBuilderFiles.file(root,UUID.randomUUID().toString());try{JEDBuilderFiles.safe(root,directory.resolve("report.json"));Files.createDirectories(directory);return directory;}catch(Exception e){throw new JEDDefinitionException("Cannot create administrative report directory "+directory,e);}}
    private static Export writeFiles(Path directory,JEDAdminSnapshot.Image image,int slices,long maximum){
        // Never creates parent directories: stopping a save cannot recreate it after temporary-world cleanup.
        try{var json=image.json();json.addProperty("capture_slices",slices);json.addProperty("maximum_capture_slice_ns",maximum);String pretty=new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(json);Files.writeString(directory.resolve("report.json"),pretty+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.writeString(directory.resolve("report.txt"),text(json),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);return new Export(directory,image.dimensions().size(),slices,maximum);}
        catch(Exception e){throw new JEDDefinitionException("Cannot export administrative report to "+directory+": "+e.getMessage(),e);}
    }
    public static String text(JsonObject json){var out=new StringBuilder("JED administrative report\nNative state was inspected without loading dimensions. Each row records its capture tick.\n\n");flatten(out,"",json);return out.toString();}
    private static void flatten(StringBuilder out,String path,JsonElement value){if(value.isJsonObject())value.getAsJsonObject().entrySet().forEach(e->flatten(out,path.isEmpty()?e.getKey():path+"."+e.getKey(),e.getValue()));else if(value.isJsonArray()){int index=0;for(var child:value.getAsJsonArray())flatten(out,path+"["+(index++)+"]",child);if(index==0)out.append(path).append(" = []\n");}else out.append(path).append(" = ").append(value).append('\n');}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var job=JOBS.get(event.getServer());if(job!=null)job.tick();}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){var job=JOBS.remove(event.getServer());if(job!=null){synchronized(job){job.stopped=true;}job.worker.shutdown();job.result.completeExceptionally(new CancellationException("Server stopped while preparing administrative report"));try{if(!job.worker.awaitTermination(5,TimeUnit.SECONDS))com.mojang.logging.LogUtils.getLogger().warn("JED report export is still closing; no further directories will be created");}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    private JEDAdminReports(){}
}
