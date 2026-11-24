package justepicdimensions.pregen;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.access.JEDAccessController;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Main-thread ownership and admission; detached durable snapshots on one ordered IO worker. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDPregenerationController {
 public record Prepared(Map<ResourceLocation,JEDPregenerationPolicy> policies,JsonObject initial,int concurrency){}
 private static final Map<MinecraftServer,Owner> OWNERS=new IdentityHashMap<>();
 private static final class Job {
  Object handle;AutoCloseable pin;ServerLevel level;boolean writing;long attempt;
  final JEDPregenerationWorkload workload=new JEDPregenerationWorkload();
 }
 private static final class Owner {
  final MinecraftServer server;final Path world;final JsonObject root;final Map<ResourceLocation,Job> jobs=new HashMap<>();final Set<ResourceLocation> monitoringPaused=new HashSet<>();final Map<ResourceLocation,String> ready=new HashMap<>(),receiptErrors=new HashMap<>();
  final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED pregeneration IO").factory());
  Map<ResourceLocation,JEDPregenerationPolicy> policies;JEDChunkyBackend backend;String unavailable="Chunky is not installed";int concurrency;boolean started,stopping,failed;long revision,savedRevision,lastSave,lastRefresh;CompletableFuture<Void> writes=CompletableFuture.completedFuture(null);
  Owner(MinecraftServer server,Prepared prepared){this.server=server;world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();root=prepared.initial().deepCopy();policies=prepared.policies();concurrency=prepared.concurrency();}
  JsonObject states(){return root.getAsJsonObject("jobs");}
  JsonObject state(ResourceLocation id){return states().has(id.toString())?states().getAsJsonObject(id.toString()):null;}
  void changed(){revision++;}
 }
 public static Prepared prepare(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
  var policies=candidate.pregenerationPolicies();var bindings=new TreeMap<ResourceLocation,JEDPregenerationPolicy>();definitions.forEach((id,d)->{if(d.pregeneration()!=null)bindings.put(id,Objects.requireNonNull(policies.get(d.pregeneration())));});
  var old=OWNERS.get(server);boolean needed=old!=null||!bindings.isEmpty()||net.neoforged.fml.ModList.get().isLoaded("chunky")||Files.exists(JEDPregenerationStore.file(server.getWorldPath(LevelResource.ROOT)));
  return new Prepared(Map.copyOf(bindings),old==null&&needed?JEDPregenerationStore.read(server.getWorldPath(LevelResource.ROOT)):null,candidate.pregenerationConcurrentJobs());
 }
 public static void publish(MinecraftServer server,Prepared prepared){
  var owner=OWNERS.get(server);if(owner==null){if(prepared.initial()==null)return;owner=new Owner(server,prepared);OWNERS.put(server,owner);}owner.policies=prepared.policies();owner.concurrency=prepared.concurrency();owner.ready.clear();
  for(var entry:owner.states().entrySet()){var id=ResourceLocation.parse(entry.getKey());var state=entry.getValue().getAsJsonObject();if(!owner.policies.containsKey(id)&&!state.has("one_time")&&!terminal(state)){state.addProperty("state","cancelling");owner.changed();}}
 }
 private static void thread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Pregeneration controls require the server thread");}
 private static Owner owner(MinecraftServer server){thread(server);var owner=OWNERS.get(server);if(owner==null)throw new JEDDefinitionException("No pregeneration policies are configured");return owner;}
 private static String state(JsonObject state){return state.get("state").getAsString();}
 private static boolean terminal(JsonObject state){return Set.of("complete","cancelled").contains(state(state));}
 private static void status(Owner owner,JsonObject state,String value){if(!state(state).equals(value)){state.addProperty("state",value);owner.changed();}}
 private static void error(Owner owner,ResourceLocation id,JsonObject state,Throwable error){String message=error.getMessage()==null?error.toString():error.getMessage();if(!state.has("error")||!state.get("error").getAsString().equals(message)){state.addProperty("error",message);owner.changed();LogUtils.getLogger().warn("JED pregeneration {}: {}",id,message);} }
 private static void release(Job job){if(job.pin!=null)try{job.pin.close();job.pin=null;job.level=null;}catch(Exception error){throw new JEDDefinitionException("Pregeneration lifecycle pin cleanup failed",error);}}
 private static Job job(Owner owner,ResourceLocation id){return owner.jobs.computeIfAbsent(id,k->new Job());}
 /** Start refuses an area above the chunk safety cap now, rather than queueing a job that fails when it launches. */
 private static void checkArea(MinecraftServer server,ResourceLocation id,JEDPregenerationPolicy policy){var level=JEDDimensionManager.load(server,id);var border=level.getWorldBorder();var spawn=level.getSharedSpawnPos();policy.plan(spawn.getX(),spawn.getZ(),border.getCenterX(),border.getCenterZ(),border.getSize());}
 /** Lifecycle refusals name a JED pregeneration job that keeps this dimension open; null when none does. */
 public static String blocking(MinecraftServer server,ResourceLocation id){var owner=OWNERS.get(server);if(owner==null)return null;var job=owner.jobs.get(id);if(job==null||job.pin==null)return null;var saved=owner.state(id);return "a JED pregeneration job ("+(saved==null?"active":state(saved))+"); cancel it with /jed pregen cancel "+id+" or let it finish";}
 private static void hold(Owner owner,ResourceLocation id,Job job){if(job.pin==null)job.pin=JEDDimensionLifecycle.pin(owner.server,id);}
 private static void later(Owner owner,Runnable action){if(!owner.stopping)owner.server.tell(new net.minecraft.server.TickTask(owner.server.getTickCount(),()->{if(!owner.stopping&&OWNERS.get(owner.server)==owner)action.run();}));}
 private static CompletableFuture<Void> flush(Owner owner){var snapshot=owner.root.deepCopy();long revision=owner.revision;var result=CompletableFuture.runAsync(()->JEDPregenerationStore.write(owner.world,snapshot),owner.io);owner.writes=result;result.whenComplete((v,error)->later(owner,()->{if(error!=null)ioFailure(owner,error);else owner.savedRevision=Math.max(owner.savedRevision,revision);}));return result;}
 private static void ioFailure(Owner owner,Throwable error){if(owner.failed)return;owner.failed=true;LogUtils.getLogger().error("JED pregeneration persistence failed; managed tasks were paused. Check storage, then shut down and restart the server.",error);if(owner.backend!=null)for(var job:owner.jobs.values())if(job.handle!=null)owner.backend.pause(job.handle);for(var player:owner.server.getPlayerList().getPlayers())player.sendSystemMessage(text("storage_failed","JED pregeneration could not save its progress. Shut down and restart the server after checking storage."));}
 private static void initialize(Owner owner){
  if(owner.started)return;owner.started=true;
  if(net.neoforged.fml.ModList.get().isLoaded("chunky"))try{owner.backend=JEDChunkyBackend.create();owner.unavailable=null;}catch(LinkageError|RuntimeException error){owner.unavailable="Unsupported Chunky integration: "+error.getMessage();LogUtils.getLogger().warn(owner.unavailable,error);}
  for(var entry:owner.states().entrySet()){var s=entry.getValue().getAsJsonObject();if(Set.of("launching","running","pausing").contains(state(s))){s.addProperty("state",s.get("manual").getAsBoolean()?"paused":"queued");owner.changed();}}
  if(owner.unavailable!=null&&!owner.policies.isEmpty())LogUtils.getLogger().warn("JED pregeneration policies remain gated: {}",owner.unavailable);
 }
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){var owner=OWNERS.get(event.getServer());if(owner!=null)initialize(owner);}
 public static boolean blocked(MinecraftServer server,ResourceLocation id,JEDAccessController.Arrival arrival){
  var owner=OWNERS.get(server);if(owner==null)return false;var policy=owner.policies.get(id);if(policy==null||policy.arrivals().equals("open"))return false;boolean ordinary=arrival==JEDAccessController.Arrival.TRAVEL||arrival==JEDAccessController.Arrival.FALLBACK;if(!ordinary&&!policy.arrivals().equals("all"))return false;
  return !policy.signature().equals(owner.ready.get(id))||!Files.isRegularFile(JEDPregenerationStore.marker(owner.world,id));
 }
 public static Component denial(ResourceLocation dimension){return text("denied","Entry to %s is waiting for required pregeneration. Use /jed pregen status %s for progress.",dimension.toString(),dimension.toString());}
 private static Component text(String key,String fallback,Object...args){return Component.translatableWithFallback("justepicdimensions.pregen."+key,fallback,args);}
 /** Includes external Chunky tasks and futures; used even when no JED policy is bound to the world. */
 public static boolean retaining(ServerLevel level){var owner=OWNERS.get(level.getServer());if(JEDPregenerationHooks.retained(level))return true;if(owner==null)return false;if(owner.started&&owner.backend==null&&net.neoforged.fml.ModList.get().isLoaded("chunky"))return true;var job=owner.jobs.get(level.dimension().location());return job!=null&&job.pin!=null||owner.backend!=null&&owner.backend.foreign(level,job==null?null:job.handle);}
 private static void refresh(Owner owner){
  for(var entry:owner.policies.entrySet()){
   var id=entry.getKey();var policy=entry.getValue();if(!JEDDimensionLifecycle.available(owner.server,id)){owner.ready.remove(id);continue;}boolean ready=false;try{ready=JEDPregenerationStore.ready(owner.world,id,policy.signature());owner.receiptErrors.remove(id);}catch(RuntimeException error){String message="Invalid pregeneration receipt: "+error.getMessage();if(!message.equals(owner.receiptErrors.put(id,message)))LogUtils.getLogger().warn("Invalid pregeneration readiness receipt for {}",id,error);}
   if(ready)owner.ready.put(id,policy.signature());else owner.ready.remove(id);
   var saved=owner.state(id);var nativeJob=owner.jobs.get(id);if(nativeJob!=null&&(nativeJob.writing||nativeJob.handle!=null))continue;
   boolean automatic=saved==null?policy.initial():(state(saved).equals("complete")||state(saved).equals("cancelled")&&saved.has("plan"))&&policy.afterReset()&&!Files.exists(JEDPregenerationStore.marker(owner.world,id));if(!ready&&automatic&&(saved==null||!saved.has("one_time"))&&JEDDimensionLifecycle.available(owner.server,id))queue(owner,id,policy);
  }
 }
 private static void queue(Owner owner,ResourceLocation id,JEDPregenerationPolicy policy){var saved=new JsonObject();saved.addProperty("policy",policy.id().toString());saved.addProperty("signature",policy.signature());saved.addProperty("token",UUID.randomUUID().toString());saved.addProperty("state","queued");saved.addProperty("manual",false);saved.addProperty("ever",true);owner.states().add(id.toString(),saved);owner.changed();}
 @SubscribeEvent public static void tick(ServerTickEvent.Post event){var owner=OWNERS.get(event.getServer());if(owner==null||owner.stopping)return;initialize(owner);if(owner.failed)return;
  try{long tick=owner.server.getTickCount();if(tick-owner.lastRefresh>=20||owner.lastRefresh==0){owner.lastRefresh=tick;refresh(owner);}int active=0;for(var job:owner.jobs.values())if(job.handle!=null||job.writing)active++;
   for(var entry:List.copyOf(owner.states().entrySet())){var id=ResourceLocation.parse(entry.getKey());var saved=entry.getValue().getAsJsonObject();var job=job(owner,id);if(terminal(saved)){if(!job.writing)release(job);continue;}if(job.writing)continue;if(job.handle!=null&&owner.backend!=null){active=step(owner,id,saved,job,active);continue;}if(!JEDDimensionLifecycle.available(owner.server,id))continue;hold(owner,id,job);
    if(owner.backend==null)continue;
    if(state(saved).equals("cancelling")){cancel(owner,id,saved,job);continue;}
    var policy=jobPolicy(owner,id);if(policy==null)continue;
    if(saved.get("manual").getAsBoolean()||owner.monitoringPaused.contains(id)||state(saved).equals("failed"))continue;
    if(active>=owner.concurrency||tick<job.attempt||JEDDimensionManager.isBusy(owner.server))continue;
    boolean allowed=job.workload.allowed(policy.workload(),false,owner.server.getPlayerCount(),tps(owner.server),System.currentTimeMillis());if(!allowed){status(owner,saved,"paused");continue;}
    if(owner.savedRevision<owner.revision){if(owner.writes.isDone())flush(owner);continue;}
    try{
     job.level=JEDDimensionManager.load(owner.server,id);var ready=justepicdimensions.spawn.JEDSpawnController.prepare(job.level);if(!ready.isDone())continue;ready.join();
     if(!saved.has("plan")){var border=job.level.getWorldBorder();var spawn=job.level.getSharedSpawnPos();saved.add("plan",policy.plan(spawn.getX(),spawn.getZ(),border.getCenterX(),border.getCenterZ(),border.getSize()).json());status(owner,saved,"launching");owner.changed();job.writing=true;var snapshot=saved.deepCopy();owner.io.execute(()->{try{if(!snapshot.has("one_time"))JEDPregenerationStore.pending(owner.world,id,snapshot);later(owner,()->{job.writing=false;flush(owner);});}catch(RuntimeException error){later(owner,()->ioFailure(owner,error));}});continue;}
     if(saved.has("one_time")&&JEDPregenerationPolicy.Plan.read(saved.getAsJsonObject("plan")).upperBound()>policy.maxChunks())throw new JEDDefinitionException("Manual selection covers up to "+String.format(java.util.Locale.ROOT,"%,d",JEDPregenerationPolicy.Plan.read(saved.getAsJsonObject("plan")).upperBound())+" chunks, above the "+String.format(java.util.Locale.ROOT,"%,d",policy.maxChunks())+" chunk safety cap. Cancel it and choose a smaller radius.");job.handle=owner.backend.launch(job.level,JEDPregenerationPolicy.Plan.read(saved.getAsJsonObject("plan")),saved.has("receipt")?saved.get("receipt").getAsString():null);saved.remove("error");status(owner,saved,"running");active++;flush(owner);
    }catch(RuntimeException error){error(owner,id,saved,error);status(owner,saved,"waiting");job.attempt=tick+100;}
   }
   if(owner.revision>owner.savedRevision&&owner.writes.isDone()&&(tick-owner.lastSave>=20)){owner.lastSave=tick;flush(owner);}
  }catch(RuntimeException error){ioFailure(owner,error);}
 }
 private static double tps(MinecraftServer server){return Math.min(20,1000/Math.max(50,server.getAverageTickTimeNanos()/1000000.0));}
 private static int step(Owner owner,ResourceLocation id,JsonObject saved,Job job,int active){
  var progress=owner.backend.progress(job.handle);saved.addProperty("chunks",progress.chunks());saved.addProperty("percent",Math.max(0,Math.min(100,progress.percent())));
  if(progress.replaced()){error(owner,id,saved,new JEDDefinitionException("A different native Chunky task replaced the managed task; foreign work was left untouched"));status(owner,saved,"failed");return active;}
  var policy=jobPolicy(owner,id);boolean cancel=state(saved).equals("cancelling")||policy==null||!JEDDimensionLifecycle.available(owner.server,id);
  if(progress.failed()){error(owner,id,saved,new JEDDefinitionException("Native chunk generation failed; cancel this job and start again to recheck the area"));if(!cancel)status(owner,saved,"failed");}
  if(progress.running()){
   boolean pause=cancel||progress.failed()||owner.monitoringPaused.contains(id)||saved.get("manual").getAsBoolean()||policy!=null&&!job.workload.allowed(policy.workload(),true,owner.server.getPlayerCount(),tps(owner.server),System.currentTimeMillis());
   if(pause){owner.backend.pause(job.handle);if(!progress.failed()||cancel)status(owner,saved,cancel?"cancelling":"pausing");}return active;
  }
  if(JEDPregenerationHooks.retained(job.level))return active;
  try{String receipt=owner.backend.saveReceipt(job.handle);if(receipt==null)saved.remove("receipt");else saved.addProperty("receipt",receipt);job.handle=null;owner.changed();
   if(cancel){cancel(owner,id,saved,job);return active-1;}
   if(progress.failed()){status(owner,saved,"failed");flush(owner);return active-1;}
   if(progress.complete()){
    status(owner,saved,"complete");job.writing=true;var snapshot=saved.deepCopy();owner.io.execute(()->{try{if(!snapshot.has("one_time"))JEDPregenerationStore.complete(owner.world,id,snapshot);later(owner,()->{job.writing=false;release(job);if(snapshot.has("one_time")){}else if(owner.policies.containsKey(id)&&!owner.policies.get(id).signature().equals(snapshot.get("signature").getAsString()))queue(owner,id,owner.policies.get(id));else owner.ready.put(id,snapshot.get("signature").getAsString());flush(owner);LogUtils.getLogger().info("JED pregeneration completed {} ({} chunks)",id,progress.chunks());});}catch(RuntimeException error){later(owner,()->ioFailure(owner,error));}});
   }else{status(owner,saved,"paused");flush(owner);}
  }catch(RuntimeException error){job.handle=null;error(owner,id,saved,error);status(owner,saved,"failed");}
  return active-1;
 }
 private static void cancel(Owner owner,ResourceLocation id,JsonObject saved,Job job){try{if(job.level==null)job.level=owner.server.getLevel(ResourceKey.create(Registries.DIMENSION,id));if(job.level==null){if(saved.has("receipt")){job.level=JEDDimensionManager.load(owner.server,id);}else{status(owner,saved,"cancelled");release(job);return;}}if(JEDPregenerationHooks.retained(job.level))return;if(!owner.backend.cancelSaved(job.level,saved.has("receipt")?saved.get("receipt").getAsString():null)){error(owner,id,saved,new JEDDefinitionException("Foreign saved or running Chunky work was left untouched"));status(owner,saved,"failed");return;}saved.remove("receipt");status(owner,saved,"cancelled");flush(owner);release(job);}catch(RuntimeException error){error(owner,id,saved,error);status(owner,saved,"failed");}}
 private static JEDPregenerationPolicy jobPolicy(Owner owner,ResourceLocation id){
  var saved=owner.state(id);if(saved==null||!saved.has("one_time"))return owner.policies.get(id);
  var manual=JEDPregenerationPolicy.decode(ResourceLocation.parse(saved.get("policy").getAsString()),new JEDSourceDocument(saved.getAsJsonObject("one_time"),"saved manual job"));var bound=owner.policies.get(id);
  return new JEDPregenerationPolicy(manual.id(),manual.shape(),manual.area(),manual.pattern(),"open",false,false,bound==null?manual.workload():bound.workload(),Math.min(manual.maxChunks(),bound==null?1000000:bound.maxChunks()));
 }
 /** One job only: no authored policy, automatic binding or readiness receipt is changed. */
 public static JsonObject startManual(MinecraftServer server,ResourceLocation id,int radius){
  thread(server);if(radius<1||radius>29999984)throw new JEDDefinitionException("Radius must be 1..29999984 blocks");
  if(!server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(id))throw new JEDDefinitionException("Unknown dimension "+id);
  var owner=OWNERS.get(server);if(owner==null)throw new JEDDefinitionException("Chunky is not installed or pregeneration is unavailable");initialize(owner);
  if(owner.failed)throw new JEDDefinitionException("Pregeneration persistence failed; restart after checking storage");if(owner.backend==null)throw new JEDDefinitionException(owner.unavailable);
  var saved=owner.state(id);var running=owner.jobs.get(id);if(saved!=null&&!terminal(saved)||running!=null&&(running.handle!=null||running.writing))throw new JEDDefinitionException("A job already exists for this dimension. Resume it, or cancel it before starting a new radius.");
  var bound=owner.policies.get(id);long cap=bound==null?1000000:bound.maxChunks();long minimum=2L*radius/16+1;if(minimum*minimum>cap)throw new JEDDefinitionException("A radius of "+radius+" covers at least "+String.format(java.util.Locale.ROOT,"%,d",minimum*minimum)+" chunks, above the "+String.format(java.util.Locale.ROOT,"%,d",cap)+" chunk safety cap. Choose a smaller radius"+(bound==null?".":" or raise max_chunks in "+bound.id()+"."));
  var json=new JsonObject();json.addProperty("format_version",1);json.addProperty("shape","square");json.addProperty("arrivals","open");var area=new JsonObject();area.addProperty("center","spawn");area.addProperty("radius",radius);json.add("area",area);json.addProperty("max_chunks",cap);
  if(bound!=null){var work=new JsonObject();work.addProperty("mode",bound.workload().mode());work.addProperty("pause_below_tps",bound.workload().pauseTps());work.addProperty("resume_above_tps",bound.workload().resumeTps());work.addProperty("recovery_seconds",bound.workload().delaySeconds());json.add("workload",work);}
  var policy=JEDPregenerationPolicy.decode(ResourceLocation.fromNamespaceAndPath("justepicdimensions","manual/"+id.getNamespace()+"/"+id.getPath()),new JEDSourceDocument(json,"manual pregeneration"));
  queue(owner,id,policy);owner.state(id).add("one_time",json);hold(owner,id,job(owner,id));flush(owner);return control(server,id,"status");
 }
 public static JsonObject control(MinecraftServer server,ResourceLocation id,String action){var owner=owner(server);var policy=jobPolicy(owner,id);var saved=owner.state(id);if(saved!=null&&terminal(saved)&&saved.has("one_time"))policy=owner.policies.get(id);
  if(action.equals("status")){var result=saved==null?new JsonObject():saved.deepCopy();result.addProperty("dimension",id.toString());result.addProperty("ready",policy!=null&&policy.signature().equals(owner.ready.get(id))&&Files.isRegularFile(JEDPregenerationStore.marker(owner.world,id)));result.addProperty("backend",owner.unavailable==null?"Chunky API 0":owner.unavailable);result.addProperty("pending",owner.server.getLevel(ResourceKey.create(Registries.DIMENSION,id))==null?0:JEDPregenerationHooks.pending(owner.server.getLevel(ResourceKey.create(Registries.DIMENSION,id))));if(policy!=null)result.addProperty("arrivals",policy.arrivals());if(owner.receiptErrors.containsKey(id))result.addProperty("error",owner.receiptErrors.get(id));return result;}
  if(owner.failed)throw new JEDDefinitionException("Pregeneration persistence failed; shut down and restart the server");if(owner.backend==null)throw new JEDDefinitionException(owner.unavailable);if(policy==null&&!action.equals("cancel"))throw new JEDDefinitionException("No pregeneration policy references "+id);
  if(action.equals("start")||action.equals("resume")){
   if(saved!=null&&state(saved).equals("failed"))throw new JEDDefinitionException("Cancel the failed job first, then start it again to recheck its area");
   if(saved!=null&&state(saved).equals("complete")&&job(owner,id).writing)return control(server,id,"status");
   if(saved==null||terminal(saved)){if(policy.signature().equals(owner.ready.get(id))&&Files.exists(JEDPregenerationStore.marker(owner.world,id)))return control(server,id,"status");checkArea(server,id,policy);queue(owner,id,policy);saved=owner.state(id);}else{if(state(saved).equals("cancelling"))throw new JEDDefinitionException("Wait for cancellation to finish");saved.addProperty("manual",false);if(!state(saved).equals("running"))status(owner,saved,"queued");saved.remove("error");owner.changed();}
   hold(owner,id,job(owner,id));
  }else if(action.equals("pause")){if(saved==null||terminal(saved))throw new JEDDefinitionException("No active JED job");saved.addProperty("manual",true);status(owner,saved,job(owner,id).handle==null?"paused":"pausing");owner.changed();if(job(owner,id).handle!=null)owner.backend.pause(job(owner,id).handle);}
  else if(action.equals("cancel")){if(saved==null||terminal(saved))throw new JEDDefinitionException("No active JED job");status(owner,saved,"cancelling");if(job(owner,id).handle!=null)owner.backend.pause(job(owner,id).handle);}
  else throw new JEDDefinitionException("Unknown pregeneration action "+action);flush(owner);return control(server,id,"status");
 }
 public static void monitoringPause(MinecraftServer server,ResourceLocation id,boolean pause){var owner=OWNERS.get(server);if(owner==null)return;if(pause)owner.monitoringPaused.add(id);else owner.monitoringPaused.remove(id);}
 public static boolean monitoringPaused(MinecraftServer server,ResourceLocation id){var owner=OWNERS.get(server);return owner!=null&&owner.monitoringPaused.contains(id);}
 public static Component feedback(JsonObject result){String state=result.has("state")?result.get("state").getAsString():"idle";var value=text("summary","%s: %s (%s%%, %s)",result.get("dimension").getAsString(),text("state."+state,state),String.format(Locale.ROOT,"%.1f",result.has("percent")?result.get("percent").getAsDouble():0),text("chunks","%s chunks checked",result.has("chunks")?result.get("chunks").getAsLong():0));if(result.has("backend")&&!result.get("backend").getAsString().equals("Chunky API 0"))value=value.copy().append("\n").append(result.get("backend").getAsString());if(result.has("error"))value=value.copy().append("\n").append(result.get("error").getAsString());return value;}
 public static void reset(MinecraftServer server,ResourceLocation id){var owner=OWNERS.get(server);if(owner==null||owner.stopping)return;owner.ready.remove(id);if(owner.backend!=null)owner.backend.reset(id);var policy=owner.policies.get(id);if(policy!=null&&policy.afterReset()&&!owner.failed){queue(owner,id,policy);flush(owner);}}
 public static List<ResourceLocation> dimensions(MinecraftServer server){var owner=OWNERS.get(server);if(owner==null)return List.of();var ids=new TreeSet<>(owner.policies.keySet());owner.states().keySet().forEach(id->ids.add(ResourceLocation.parse(id)));return List.copyOf(ids);}
 @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stopping(ServerStoppingEvent event){var owner=OWNERS.get(event.getServer());if(owner==null)return;owner.stopping=true;if(owner.backend!=null)for(var job:owner.jobs.values())if(job.handle!=null)owner.backend.pause(job.handle);try{flush(owner).get(15,TimeUnit.SECONDS);}catch(Exception error){LogUtils.getLogger().error("Could not persist pregeneration restart intent",error);}}
 @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event){var owner=OWNERS.remove(event.getServer());if(owner==null)return;try{
  if(owner.backend!=null)for(var entry:owner.jobs.entrySet()){var job=entry.getValue();var saved=owner.state(entry.getKey());if(job.handle!=null&&!JEDPregenerationHooks.retained(job.level)&&!owner.backend.progress(job.handle).running())try{String receipt=owner.backend.saveReceipt(job.handle);if(receipt!=null)saved.addProperty("receipt",receipt);else saved.remove("receipt");owner.changed();}catch(RuntimeException error){LogUtils.getLogger().warn("Pregeneration ownership could not be saved for {}",entry.getKey(),error);}release(job);}
  flush(owner).get(15,TimeUnit.SECONDS);
 }catch(Exception error){LogUtils.getLogger().error("Could not persist final pregeneration ownership receipts",error);}finally{for(var level:event.getServer().getAllLevels())JEDPregenerationHooks.forget(level);owner.io.shutdown();}}
 private JEDPregenerationController(){}
}
