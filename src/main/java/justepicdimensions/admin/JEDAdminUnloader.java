package justepicdimensions.admin;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One loaded-instance snapshot, rechecked one dimension per real tick. Never removes native tickets. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDAdminUnloader {
    public static final long DRAIN_NS=10_000_000_000L;
    public record Options(boolean drainIdle,boolean includeTemporary){}
    public record Entry(ResourceLocation id,String action,String outcome,String reason,long waitedNanos,long operationNanos){}
    public record Outcome(List<Entry> entries,String stopped){public Outcome{entries=List.copyOf(entries);}public long count(String result){return entries.stream().filter(e->e.outcome().equals(result)).count();}}
    private static final Map<MinecraftServer,Job> JOBS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Outcome> LAST=new IdentityHashMap<>();
    private static final ThreadLocal<MinecraftServer> DISPATCH=new ThreadLocal<>();
    public static boolean busy(MinecraftServer server){return JOBS.containsKey(server);}
    public static void requireIdle(MinecraftServer server){JEDAdminSnapshot.thread(server);if(busy(server)&&DISPATCH.get()!=server)throw new JEDDefinitionException("A bulk dimension unload is in progress");}
    /** The lifecycle coordinator closes the selected temporary level later, after its worker reserves intent. */
    public static void requireClose(MinecraftServer server,ResourceLocation id){JEDAdminSnapshot.thread(server);var job=JOBS.get(server);if(job!=null&&DISPATCH.get()!=server&&!(job.closing!=null&&job.current!=null&&job.current.getKey().equals(id)))throw new JEDDefinitionException("A bulk dimension unload is in progress");}
    public static JsonObject diagnostics(MinecraftServer server){JEDAdminSnapshot.thread(server);var result=new JsonObject();var job=JOBS.get(server);result.addProperty("busy",job!=null);if(job!=null){result.addProperty("remaining",job.queue.size());result.addProperty("drain_idle",job.options.drainIdle());result.addProperty("include_temporary",job.options.includeTemporary());if(job.current!=null)result.addProperty("dimension",job.current.getKey().toString());result.addProperty("phase",job.closing!=null?"closing":job.waitStart!=0?"draining":"queued");}return result;}
    public static Optional<Outcome> last(MinecraftServer server){JEDAdminSnapshot.thread(server);return Optional.ofNullable(LAST.get(server));}
    public static CompletableFuture<Outcome> pending(MinecraftServer server){var job=JOBS.get(server);if(job==null)throw new JEDDefinitionException("No bulk unload is running");return job.result;}
    public static String reason(MinecraftServer server,ResourceLocation id,boolean includeTemporary){
        JEDAdminSnapshot.thread(server);if(id.equals(Level.OVERWORLD.location()))return "overworld";
        var definition=JEDDefinitionManager.state(server).definitions().get(id);
        if(definition!=null&&definition.lifecycle().temporary()&&!includeTemporary)return "temporary (requires include-temporary; unloading resets files)";
        if(!JEDDimensionLifecycle.available(server,id))return "disabled or completing lifecycle work";
        var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));if(level==null)return "not loaded";
        String blocker=JEDDimensionLifecycle.blocker(server,id);if(blocker!=null)return blocker;
        return justepicdimensions.spawn.JEDSpawnChunks.retaining(level)?"retained spawn chunks":null;
    }
    public static List<Entry> preview(MinecraftServer server,Options options){JEDAdminSnapshot.thread(server);var result=new ArrayList<Entry>();for(var id:loaded(server).keySet()){var why=reason(server,id,options.includeTemporary());var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));boolean wait=options.drainIdle()&&level!=null&&canDrain(why,level)&&(why!=null||level.getChunkSource().getPendingTasksCount()>0);result.add(new Entry(id,action(server,id),wait?"would_wait":why==null?"eligible":"skipped",why==null?(wait?"pending chunk work":"none"):why,0,0));}return List.copyOf(result);}
    public static CompletableFuture<Outcome> run(CommandSourceStack source,Options options){
        var server=source.getServer();JEDAdminSnapshot.thread(server);if(!source.hasPermission(2))throw new JEDDefinitionException("Administrative commands require operator level 2");
        JEDDefinitionHooks.requireIdle(server);if(JEDDimensionManager.isBusy(server)||JEDDimensionCreation.preparing(server))throw new JEDDefinitionException("Wait for the active builder/dimension operation");
        var job=new Job(server,options,loaded(server));JOBS.put(server,job);return job.result;
    }
    private static Map<ResourceLocation,ServerLevel> loaded(MinecraftServer server){var sorted=new TreeMap<ResourceLocation,ServerLevel>();justepicdimensions.dimension.JEDServerLevels.view(server).forEach((key,level)->sorted.put(key.location(),level));return sorted;}
    private static String action(MinecraftServer server,ResourceLocation id){var d=JEDDefinitionManager.state(server).definitions().get(id);return d!=null&&d.lifecycle().temporary()?"reset":"unload";}
    private static boolean canDrain(String reason,ServerLevel level){return (reason==null||reason.equals("chunk tickets"))&&level.getForcedChunks().isEmpty()&&level.players().isEmpty();}
    private static final class Job {
        final MinecraftServer server;final Options options;final ArrayDeque<Map.Entry<ResourceLocation,ServerLevel>> queue;
        final CompletableFuture<Outcome> result=new CompletableFuture<>(){@Override public boolean cancel(boolean interrupt){return false;}};
        final List<Entry> entries=new ArrayList<>();Map.Entry<ResourceLocation,ServerLevel> current;long waitStart,closeStart,operationNanos;CompletableFuture<Void> closing;
        Job(MinecraftServer server,Options options,Map<ResourceLocation,ServerLevel> loaded){this.server=server;this.options=options;queue=new ArrayDeque<>(loaded.entrySet());}
        void tick(){
            if(closing!=null){if(!closing.isDone())return;Throwable failure=null;try{closing.join();}catch(Throwable e){failure=justepicdimensions.builder.JEDBuilderService.root(e);}record(failure==null?"completed":"failed",failure==null?"none":failure.getMessage(),operationNanos);closing=null;if(failure!=null&&(JEDDimensionLifecycle.busy(server)||JEDDimensionManager.recoveryRequiredForInspection(server))){finish("recovery required after lifecycle failure");return;}return;}
            if(current==null){current=queue.poll();waitStart=0;if(current==null){finish("");return;}}
            var id=current.getKey();var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));
            if(level!=current.getValue()){record("skipped","loaded instance changed",0);return;}
            String why=reason(server,id,options.includeTemporary());
            if(options.drainIdle()&&level!=null&&canDrain(why,level)&&(why!=null||level.getChunkSource().getPendingTasksCount()>0)){
                long now=System.nanoTime();if(waitStart==0)waitStart=now;if(now-waitStart<DRAIN_NS)return;record("skipped","idle drain timed out: "+(why==null?"pending chunk work":why),0);return;
            }
            if(why!=null){record("skipped",why,0);return;}
            try{closeStart=System.nanoTime();DISPATCH.set(server);closing=JEDDimensionManager.unloadAsync(server,id);closing.whenComplete((v,e)->operationNanos=System.nanoTime()-closeStart);}
            catch(Throwable e){record("failed",e.getMessage(),System.nanoTime()-closeStart);if(JEDDimensionLifecycle.busy(server)||JEDDimensionManager.recoveryRequiredForInspection(server))finish("recovery required after lifecycle failure");}
            finally{DISPATCH.remove();}
        }
        void record(String outcome,String why,long close){entries.add(new Entry(current.getKey(),action(server,current.getKey()),outcome,why,waitStart==0?0:Math.max(0,(closeStart==0?System.nanoTime():closeStart)-waitStart),close));current=null;waitStart=closeStart=0;}
        void finish(String stopped){
            if(JOBS.get(server)!=this)return;if(current!=null){record(closing==null?"skipped":"interrupted",stopped,0);closing=null;}while(!queue.isEmpty()){var item=queue.poll();entries.add(new Entry(item.getKey(),action(server,item.getKey()),"skipped",stopped,0,0));}
            JOBS.remove(server,this);var outcome=new Outcome(entries,stopped);LAST.put(server,outcome);result.complete(outcome);
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var job=JOBS.get(event.getServer());if(job!=null)try{job.tick();}catch(Throwable e){job.finish("bulk unload stopped: "+e.getMessage());}}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){var job=JOBS.get(event.getServer());if(job!=null)job.finish("server stopping; any durable lifecycle work uses normal restart recovery");LAST.remove(event.getServer());}
    private JEDAdminUnloader(){}
}
