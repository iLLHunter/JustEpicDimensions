package justepicdimensions.schedule;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.access.JEDAccessController;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static justepicdimensions.schedule.JEDLifecycleSchedulePolicy.*;

/** Per-save coordinator. World-file changes remain exclusively owned by native JED lifecycle. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDLifecycleScheduleController {
    public record Binding(ResourceLocation dimension,ResourceLocation policy,Rule rule){public String key(){return JEDScheduleStore.key(dimension,policy,rule.id());}}
    public record Prepared(Map<String,Binding> bindings,JsonObject initial,JEDLifecycleStore journal){}
    private static final Map<MinecraftServer,Owner> OWNERS=new IdentityHashMap<>();
    private static final class Attempt {final UUID token=UUID.randomUUID();final List<Binding> jobs;final JsonObject metadata;boolean launched,cancelled;Attempt(List<Binding> jobs,JsonObject metadata){this.jobs=List.copyOf(jobs);this.metadata=metadata;}}
    private static final class Owner {
        final MinecraftServer server;final Path world;final ExecutorService io=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED schedule IO").factory());
        final JsonObject root;final Set<String> seen=new HashSet<>(),errors=new HashSet<>();Map<String,Binding> bindings=Map.of();JEDLifecycleStore journal;Attempt attempt;
        volatile boolean stopping;boolean ready,failed,reading;long revision,savedRevision,nextSave,nextRead,nextRetention,nextAttempt;int saves;String purge;
        Owner(MinecraftServer server,Prepared prepared){this.server=server;world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();root=prepared.initial().deepCopy();journal=prepared.journal();}
        JsonObject states(){return root.getAsJsonObject("states");}
        void put(String key,JsonObject state){if(!Objects.equals(states().get(key),state)){states().add(key,state);revision++;}}
    }
    public static Prepared prepare(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var policies=candidate.lifecycleSchedules();var bindings=new TreeMap<String,Binding>();
        definitions.forEach((dim,definition)->{var policy=definition.lifecycleSchedule();if(policy!=null)for(var rule:policies.get(policy).rules()){var binding=new Binding(dim,policy,rule);bindings.put(binding.key(),binding);}});
        var old=OWNERS.get(server);var world=server.getWorldPath(LevelResource.ROOT);boolean needed=old!=null||!bindings.isEmpty()||Files.exists(JEDScheduleStore.file(world));
        return new Prepared(Collections.unmodifiableMap(bindings),needed&&old==null?JEDScheduleStore.read(world):null,needed&&old==null?JEDLifecycleStore.read(world):null);
    }
    public static void publish(MinecraftServer server,Prepared prepared){
        var owner=OWNERS.get(server);if(owner==null){if(prepared.initial()==null)return;owner=new Owner(server,prepared);OWNERS.put(server,owner);}
        for(String key:List.copyOf(owner.states().keySet()))if(!prepared.bindings().containsKey(key)){
            var state=owner.states().getAsJsonObject(key).deepCopy();state.addProperty("active",false);
            boolean unstarted=owner.attempt!=null&&!owner.attempt.launched&&owner.attempt.jobs.stream().anyMatch(b->b.key().equals(key));
            if(unstarted)owner.attempt.cancelled=true;
            if(unstarted||!state.has("native_token"))clearPending(state);owner.put(key,state);
        }
        owner.bindings=prepared.bindings();if(!owner.ready&&server.getTickCount()>0){JEDScheduleClock.atLeast(server,owner.root.get("server_ticks").getAsLong());owner.ready=true;refresh(owner);}
    }
    private static long now(Owner owner,Rule rule){return rule.clock()==Clock.SERVER_TICKS?JEDScheduleClock.ticks(owner.server):System.currentTimeMillis();}
    private static JsonObject state(Owner owner,Binding binding){
        var rule=binding.rule();long now=now(owner,rule);var raw=owner.states().get(binding.key());
        if(raw==null){var value=JEDScheduleMachine.initial(rule,now,rule.initiallyOpen());owner.put(binding.key(),value);return value;}
        var value=raw.getAsJsonObject();if(value.has("native_token"))return value;
        boolean changed=!rule.signature().equals(value.get("signature").getAsString())||value.has("active")&&!value.get("active").getAsBoolean();
        if(!changed)return value;
        var next=JEDScheduleMachine.initial(rule,now,rule.initiallyOpen());boolean compatible=value.get("clock").getAsString().equals(rule.clock().name().toLowerCase(Locale.ROOT))&&value.has("action")&&value.get("action").getAsString().equals(rule.action().name().toLowerCase(Locale.ROOT));
        if(compatible&&rule.onTimingChange().equals("recompute")){
            long anchor=value.has("last_completed")?value.get("last_completed").getAsLong():value.has("activated_at")?value.get("activated_at").getAsLong():now;
            if(rule.action()==Action.RESET){next.addProperty("activated_at",anchor);next.addProperty("next_due",rule.next(anchor));if(value.has("last_completed"))next.add("last_completed",value.get("last_completed").deepCopy());}
            else if(rule.clock()!=Clock.CALENDAR){long first=value.has("first_open")?value.get("first_open").getAsLong():anchor;next.addProperty("first_open",first);long latest=now<first?first:Math.addExact(first,Math.multiplyExact((now-first)/rule.interval(),rule.interval()));next.addProperty("open_until",now<first?0:rule.closes(latest));next.addProperty("next_due",now<first?first:rule.next(latest));}
        }
        if(rule.action()==Action.AVAILABILITY&&value.has("was_open"))next.add("was_open",value.get("was_open").deepCopy());owner.put(binding.key(),next);return next;
    }
    /** Same admission path is used by native portals, commands, passenger groups, login and respawn. */
    public static boolean allows(MinecraftServer server,ResourceLocation dimension,JEDAccessController.Arrival arrival){
        var owner=OWNERS.get(server);if(owner==null||!owner.ready)return true;boolean windows=false,windowAllowed=false;
        for(var binding:owner.bindings.values())if(binding.dimension().equals(dimension)){
            var rule=binding.rule();var state=state(owner,binding);if(state.has("native_token")&&!rule.signature().equals(state.get("signature").getAsString()))continue;long now=now(owner,rule);boolean ordinary=arrival==JEDAccessController.Arrival.TRAVEL||arrival==JEDAccessController.Arrival.FALLBACK;
            if(rule.action()==Action.RESET){boolean pending=state.has("pending_due")||state.has("native_token")||now>=state.get("next_due").getAsLong();if(pending&&!rule.arrivals().equals("open")&&(ordinary||rule.arrivals().equals("all")))return false;}
            else{windows=true;windowAllowed|=JEDScheduleMachine.openAt(rule,state,now)||!ordinary&&rule.arrivals().equals("ordinary");}
        }return !windows||windowAllowed;
    }
    public static boolean blocked(MinecraftServer server,ResourceLocation dimension,JEDAccessController.Arrival arrival){return !allows(server,dimension,arrival);}
    public static Component denial(ResourceLocation dimension){return message("denied","Entry to %s is closed by its lifecycle schedule.",dimension.toString());}
    private static List<ServerPlayer> players(Owner owner,ResourceLocation dimension){var result=new ArrayList<ServerPlayer>();for(var player:owner.server.getPlayerList().getPlayers())if(player.serverLevel().dimension().location().equals(dimension))result.add(player);return result;}
    private static Component message(String key,String fallback,Object...args){return Component.translatableWithFallback("justepicdimensions.schedule."+key,fallback,args);}
    private static void announce(Owner owner,Binding binding,Component message){
        String audience=binding.rule().warnings().audience();for(var player:owner.server.getPlayerList().getPlayers())if(audience.equals("all")||player.serverLevel().dimension().location().equals(binding.dimension())||audience.equals("affected_operators")&&owner.server.getPlayerList().isOp(player.getGameProfile()))player.sendSystemMessage(message);LogUtils.getLogger().info("JED schedule: {}",message.getString());
    }
    private static void warning(Owner owner,Binding binding,JsonObject state,long now){
        if(!state.has("warning_end"))return;long end=state.get("warning_end").getAsLong();if(state.has("last_notice")&&state.get("last_notice").getAsLong()==end)return;
        long divisor=binding.rule().clock()==Clock.SERVER_TICKS?20:1000;long seconds=Math.max(0,end-now);seconds=seconds/divisor+(seconds%divisor==0?0:1);
        announce(owner,binding,message("warning","Scheduled evacuation from %s in %s seconds.",binding.dimension().toString(),seconds));state.addProperty("last_notice",end);owner.put(binding.key(),state);
    }
    @SubscribeEvent public static void arrival(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player)arrival(player);}
    @SubscribeEvent public static void arrival(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event){if(event.getEntity() instanceof ServerPlayer player)arrival(player);}
    @SubscribeEvent public static void arrival(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event){if(event.getEntity() instanceof ServerPlayer player)arrival(player);}
    private static void arrival(ServerPlayer player){var owner=OWNERS.get(player.server);if(owner==null||!owner.ready)return;for(var binding:owner.bindings.values())if(binding.dimension().equals(player.level().dimension().location())&&owner.states().has(binding.key())){var state=owner.states().getAsJsonObject(binding.key());long now=now(owner,binding.rule());if(state.has("warning_end")&&state.get("warning_end").getAsLong()>now){long scale=binding.rule().clock()==Clock.SERVER_TICKS?20:1000;long seconds=(state.get("warning_end").getAsLong()-now+scale-1)/scale;player.sendSystemMessage(message("warning","Scheduled evacuation from %s in %s seconds.",binding.dimension().toString(),seconds));}}}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){var owner=OWNERS.get(event.getServer());if(owner==null)return;JEDScheduleClock.atLeast(owner.server,owner.root.get("server_ticks").getAsLong());owner.ready=true;refresh(owner);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var owner=OWNERS.get(event.getServer());if(owner==null||owner.stopping||!owner.ready||owner.failed)return;
        try{evaluate(owner);}catch(RuntimeException error){error(owner,"evaluation",error);}
    }
    private static void evaluate(Owner owner){
        var ready=new TreeMap<ResourceLocation,List<Binding>>();var evaluations=new LinkedHashMap<Binding,JEDScheduleMachine.Evaluation>();var open=new HashSet<ResourceLocation>();var closedNow=new HashSet<Binding>();
        for(var binding:owner.bindings.values()){
            var rule=binding.rule();var original=state(owner,binding);boolean first=owner.seen.add(binding.key());long now=now(owner,rule);int occupants=players(owner,binding.dimension()).size();
            var result=original.has("native_token")?new JEDScheduleMachine.Evaluation(original.deepCopy(),JEDScheduleMachine.Kind.NATIVE):rule.action()==Action.RESET?JEDScheduleMachine.reset(rule,original,now,occupants,first):JEDScheduleMachine.window(rule,original,now,occupants);if(!original.has("native_token")&&rule.action()==Action.AVAILABILITY&&original.has("was_open")&&original.get("was_open").getAsBoolean()&&!result.state().get("was_open").getAsBoolean())closedNow.add(binding);owner.put(binding.key(),result.state());evaluations.put(binding,result);
            if(!original.has("native_token")&&rule.action()==Action.AVAILABILITY&&JEDScheduleMachine.openAt(rule,result.state(),now))open.add(binding.dimension());
        }
        for(var entry:evaluations.entrySet()){
            var binding=entry.getKey();var result=entry.getValue();var state=result.state();long now=now(owner,binding.rule());
            if(result.kind()!=JEDScheduleMachine.Kind.NATIVE&&binding.rule().action()==Action.AVAILABILITY&&!state.get("was_open").getAsBoolean()&&open.contains(binding.dimension())){if(state.has("warning_end")){state.remove("warning_end");state.remove("last_notice");owner.put(binding.key(),state);}continue;}
            if(closedNow.contains(binding)&&!open.contains(binding.dimension())&&binding.rule().occupied().equals("stay"))announce(owner,binding,message("closed","Entry window for %s has closed. Players already inside may stay.",binding.dimension().toString()));
            switch(result.kind()){
                case WARNING->warning(owner,binding,state,now);
                case DEFERRED->{if(!state.has("last_notice")){announce(owner,binding,message("waiting","Scheduled reset of %s is waiting for the dimension to become empty.",binding.dimension().toString()));state.addProperty("last_notice",state.get("pending_due").getAsLong());owner.put(binding.key(),state);}}
                case READY->ready.computeIfAbsent(binding.dimension(),key->new ArrayList<>()).add(binding);
                case EVACUATE->{if(!JEDDimensionManager.isBusy(owner.server))evacuate(owner,binding);}
                default->{}
            }
        }
        if(owner.attempt==null&&!owner.reading&&JEDScheduleClock.ticks(owner.server)>=owner.nextAttempt&&!JEDDimensionManager.isBusy(owner.server))for(var jobs:ready.values()){launch(owner,jobs);if(owner.attempt!=null)break;}
        long ticks=JEDScheduleClock.ticks(owner.server);if(ticks>=owner.nextRead&&hasNative(owner)){owner.nextRead=ticks+20;refresh(owner);}
        if(ticks>=owner.nextSave&&owner.revision>owner.savedRevision&&owner.saves==0){owner.nextSave=ticks+20;flush(owner,null);}
        if(ticks>=owner.nextRetention&&owner.attempt==null&&owner.purge==null&&!owner.reading&&!JEDDimensionManager.isBusy(owner.server)){owner.nextRetention=ticks+1200;retention(owner);}
    }
    private static boolean hasNative(Owner owner){for(var raw:owner.states().entrySet())if(raw.getValue().getAsJsonObject().has("native_token"))return true;return false;}
    private static void launch(Owner owner,List<Binding> jobs){
        owner.nextAttempt=JEDScheduleClock.ticks(owner.server)+20;var dimension=jobs.getFirst().dimension();var reason=JEDDimensionLifecycle.blocker(owner.server,dimension);boolean relocating=jobs.stream().allMatch(binding->binding.rule().relocate());if(reason!=null&&!(reason.equals("players")&&relocating))return;
        if(jobs.stream().anyMatch(binding->binding.rule().occupied().equals("defer"))&&!players(owner,dimension).isEmpty())return;
        var metadata=new JsonObject();metadata.addProperty("format_version",1);var contributors=new JsonArray();for(var binding:jobs){var state=owner.states().getAsJsonObject(binding.key());var job=new JsonObject();job.addProperty("key",binding.key());job.add("due",state.get("pending_due").deepCopy());job.addProperty("clock",binding.rule().clock().name().toLowerCase(Locale.ROOT));var retention=new JsonObject();retention.addProperty("mode",binding.rule().retention().mode());retention.addProperty("value",binding.rule().retention().value());job.add("retention",retention);contributors.add(job);}metadata.add("jobs",contributors);
        var attempt=new Attempt(jobs,metadata);owner.attempt=attempt;for(var binding:jobs){var state=owner.states().getAsJsonObject(binding.key()).deepCopy();state.addProperty("native_token",attempt.token.toString());state.add("native_due",state.get("pending_due").deepCopy());owner.put(binding.key(),state);}
        flush(owner,()->{
            if(attempt.cancelled||owner.attempt!=attempt){cancelAttempt(owner,attempt);return;}
            if(JEDDimensionManager.isBusy(owner.server)){cancelAttempt(owner,attempt);return;}
            try{
                var fallback=fallback(owner,dimension);for(var player:players(owner,dimension))if(!JEDAccessController.check(player,fallback,false,JEDAccessController.Arrival.FALLBACK).allowed()){cancelAttempt(owner,attempt);return;}
                attempt.launched=true;JEDDimensionLifecycle.operateScheduled(owner.server,dimension,relocating,attempt.token,metadata).whenComplete((v,error)->{if(owner.stopping)return;if(error!=null)error(owner,"reset "+dimension,error);owner.attempt=null;refresh(owner);});
            }catch(RuntimeException error){error(owner,"reset "+dimension,error);cancelAttempt(owner,attempt);}
        });
    }
    private static void cancelAttempt(Owner owner,Attempt attempt){if(owner.attempt==attempt)owner.attempt=null;for(var binding:attempt.jobs)if(owner.states().has(binding.key())){var state=owner.states().getAsJsonObject(binding.key()).deepCopy();state.remove("native_token");state.remove("native_due");owner.put(binding.key(),state);}}
    private static ResourceLocation fallback(Owner owner,ResourceLocation dimension){var definition=JEDDefinitionManager.state(owner.server).definitions().get(dimension);return definition==null?Level.OVERWORLD.location():definition.lifecycle().fallback();}
    private static void evacuate(Owner owner,Binding binding){
        try{
            var fallback=JEDDimensionManager.load(owner.server,fallback(owner,binding.dimension()));for(var player:players(owner,binding.dimension())){
                var target=JEDAccessController.destination(player,fallback,JEDAccessController.Arrival.FALLBACK);var ready=justepicdimensions.spawn.JEDSpawnController.prepare(target);if(!ready.isDone()||ready.isCompletedExceptionally())continue;
                player.teleportTo(target,target.getSharedSpawnPos().getX()+.5,target.getSharedSpawnPos().getY(),target.getSharedSpawnPos().getZ()+.5,target.getSharedSpawnAngle(),0);
            }
            if(players(owner,binding.dimension()).isEmpty()){var state=owner.states().getAsJsonObject(binding.key()).deepCopy();state.remove("warning_end");state.remove("last_notice");owner.put(binding.key(),state);}
        }catch(RuntimeException error){error(owner,"evacuation "+binding.dimension(),error);}
    }
    private static void refresh(Owner owner){
        if(owner.reading||owner.stopping)return;var tokens=new HashSet<String>();for(var raw:owner.states().entrySet())if(raw.getValue().getAsJsonObject().has("native_token"))tokens.add(raw.getValue().getAsJsonObject().get("native_token").getAsString());owner.reading=true;owner.io.execute(()->{try{var journal=JEDLifecycleStore.read(owner.world);var present=new HashSet<String>();for(String token:tokens)if(Files.exists(JEDDimensionFiles.trash(owner.world,token).resolve("entry.json")))present.add(token);
            post(owner,()->{owner.reading=false;if(owner.stopping)return;owner.journal=journal;for(String key:List.copyOf(owner.states().keySet())){
                var state=owner.states().getAsJsonObject(key);if(!state.has("native_token"))continue;String token=state.get("native_token").getAsString();var receipt=journal.scheduledCompletion(key);var binding=owner.bindings.get(key);
                if(receipt!=null&&receipt.get("token").getAsString().equals(token)){
                    if(receipt.has("status")&&receipt.get("status").getAsString().equals("aborted")){var retry=state.deepCopy();retry.remove("native_token");retry.remove("native_due");if(binding==null)clearPending(retry);owner.put(key,retry);continue;}
                    long completion=receipt.get("clock").getAsString().equals("server_ticks")?receipt.get("completed_ticks").getAsLong():receipt.get("completed_at").getAsLong();boolean compatible=binding!=null&&state.get("clock").getAsString().equals(binding.rule().clock().name().toLowerCase(Locale.ROOT))&&state.get("action").getAsString().equals(binding.rule().action().name().toLowerCase(Locale.ROOT));var changed=binding==null?state.deepCopy():compatible?JEDScheduleMachine.completed(binding.rule(),state,completion):JEDScheduleMachine.initial(binding.rule(),now(owner,binding.rule()),binding.rule().initiallyOpen());if(binding==null){clearPending(changed);changed.addProperty("last_completed",completion);changed.addProperty("active",false);}owner.put(key,changed);JEDScheduleClock.atLeast(owner.server,receipt.get("completed_ticks").getAsLong());
                }else if(owner.attempt==null&&(journal.operation()==null||!journal.operation().get("token").getAsString().equals(token))){if(present.contains(token)){error(owner,"recovery "+token,new IllegalStateException("History exists without a completion receipt; retained pending operation"));continue;}var changed=state.deepCopy();changed.remove("native_token");changed.remove("native_due");if(binding==null)clearPending(changed);owner.put(key,changed);}
            }if(owner.revision>owner.savedRevision)flush(owner,null);});
        }catch(Throwable error){post(owner,()->{owner.reading=false;error(owner,"journal read",error);});}});
    }
    private static void flush(Owner owner,Runnable done){
        owner.root.addProperty("server_ticks",JEDScheduleClock.ticks(owner.server));var snapshot=owner.root.deepCopy();long revision=owner.revision;owner.saves++;
        owner.io.execute(()->{try{JEDScheduleStore.write(owner.world,snapshot);post(owner,()->{owner.saves--;owner.savedRevision=Math.max(owner.savedRevision,revision);if(!owner.stopping&&!owner.failed&&done!=null)done.run();});}catch(Throwable error){post(owner,()->{owner.saves--;owner.failed=true;error(owner,"progress write; scheduled operations paused until restart",error);});}});
    }
    private static void retention(Owner owner){
        owner.reading=true;owner.io.execute(()->{try{var eligible=JEDScheduleHistory.eligible(owner.world,System.currentTimeMillis());post(owner,()->{owner.reading=false;if(owner.stopping||eligible.isEmpty()||JEDDimensionManager.isBusy(owner.server))return;owner.purge=eligible.getFirst();try{JEDDimensionLifecycle.purge(owner.server,owner.purge).whenComplete((v,error)->{owner.purge=null;if(error!=null)error(owner,"history retention",error);});}catch(RuntimeException error){owner.purge=null;error(owner,"history retention",error);}});}catch(Throwable error){post(owner,()->{owner.reading=false;error(owner,"history retention",error);});}});
    }
    private static void post(Owner owner,Runnable action){if(owner.stopping)return;owner.server.tell(new net.minecraft.server.TickTask(owner.server.getTickCount(),()->{if(!owner.stopping)action.run();}));}
    private static void error(Owner owner,String stage,Throwable error){String key=stage+":"+error.getMessage();if(owner.errors.add(key))LogUtils.getLogger().error("JED lifecycle schedule {}: {}",stage,error.getMessage(),error);}
    private static void clearPending(JsonObject state){for(String field:List.of("pending_due","warning_end","last_notice","native_token","native_due","blocked_reason"))state.remove(field);}
    public static JsonObject inspect(MinecraftServer server){var owner=OWNERS.get(server);return owner==null?JEDScheduleStore.empty():owner.root.deepCopy();}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void stopping(ServerStoppingEvent event){var owner=OWNERS.get(event.getServer());if(owner==null)return;owner.stopping=true;owner.root.addProperty("server_ticks",JEDScheduleClock.ticks(owner.server));var snapshot=owner.root.deepCopy();owner.io.execute(()->JEDScheduleStore.write(owner.world,snapshot));owner.io.shutdown();}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var owner=OWNERS.remove(event.getServer());if(owner==null)return;try{if(!owner.io.awaitTermination(30,TimeUnit.SECONDS))LogUtils.getLogger().error("JED schedule IO did not finish before shutdown; durable reset receipts retain recovery identity");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    private JEDLifecycleScheduleController(){}
}
