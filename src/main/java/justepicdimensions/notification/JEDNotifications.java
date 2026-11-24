package justepicdimensions.notification;

import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.api.lifecycle.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Durable delivery with independent receipts. Disk IO never runs in a notification handler/server tick. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDNotifications {
    private static final Map<MinecraftServer,Owner> OWNERS=new IdentityHashMap<>();
    private static final class Owner {
        final MinecraftServer server;final Path world;final UUID session=UUID.randomUUID();
        final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED notification IO").factory());
        final Map<UUID,JEDNotificationStore.Pending> entries=new LinkedHashMap<>();final Set<UUID> delivered=new HashSet<>(),working=new HashSet<>();
        final ArrayDeque<UUID> queue=new ArrayDeque<>();final Set<UUID> queued=new HashSet<>();
        volatile boolean stopping;volatile long maximumIoNanos;boolean initialized;long maximumHandlerNanos,deliveries,receipts;
        Owner(MinecraftServer server){this.server=server;world=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();}
    }
    private static Owner owner(MinecraftServer server){thread(server);return OWNERS.computeIfAbsent(server,Owner::new);}
    public static JEDNotificationStore.Pending prepare(MinecraftServer server,JEDOperation.Kind kind,UUID id,Collection<ResourceLocation> dimensions){
        var owner=owner(server);return new JEDNotificationStore.Pending(snapshot(server,kind,id,dimensions),owner.session,consumers(kind),true,"",1,0,0,Set.of());
    }
    public static JEDNotificationStore.Pending intent(MinecraftServer server,JEDOperation.Kind kind,UUID id,Collection<ResourceLocation> dimensions,String recovery){
        var value=prepare(server,kind,id,dimensions);return new JEDNotificationStore.Pending(value.operation,value.initialSession,value.consumers,false,recovery,1,0,0,Set.of());
    }
    private static Set<ResourceLocation> consumers(JEDOperation.Kind kind){var result=new TreeSet<ResourceLocation>();JEDNotificationRegistry.consumers().forEach((id,type)->{if(type.kinds().contains(kind))result.add(id);});return Set.copyOf(result);}
    private static JEDOperation snapshot(MinecraftServer server,JEDOperation.Kind kind,UUID id,Collection<ResourceLocation> dimensions){
        thread(server);var states=new TreeMap<ResourceLocation,JEDOperation.DimensionState>();var registry=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        for(var dimension:dimensions)states.put(dimension,new JEDOperation.DimensionState(registry.containsKey(dimension),JEDDimensionLifecycle.enabled(server,dimension),server.levelKeys().contains(ResourceKey.create(Registries.DIMENSION,dimension))));
        return new JEDOperation(id,kind,Instant.now(),JEDDefinitionManager.state(server).revision(),states);
    }
    /** Called by the owning lifecycle worker BEFORE it removes its completed durable journal intent. */
    public static void persist(Path world,JEDNotificationStore.Pending completion){JEDNotificationStore.enqueue(world,completion);}
    public static CompletableFuture<Void> publish(MinecraftServer server,JEDOperation.Kind kind,Collection<ResourceLocation> ids){return publish(server,prepare(server,kind,UUID.randomUUID(),ids));}
    public static CompletableFuture<Void> publish(MinecraftServer server,JEDNotificationStore.Pending value){
        var owner=owner(server);var result=new CompletableFuture<Void>(){@Override public boolean cancel(boolean interrupt){return false;}};
        io(owner,()->JEDNotificationStore.enqueue(owner.world,value)).whenComplete((pending,error)->{
            if(error!=null)result.completeExceptionally(error);else{accept(owner,pending);result.complete(null);}
        });return result;
    }
    public static Set<ResourceLocation> reloadDimensions(MinecraftServer server){thread(server);var ids=new TreeSet<>(server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet());ids.addAll(JEDDefinitionManager.state(server).definitions().keySet());return Set.copyOf(ids);}
    /** Producer already persisted the outbox on its worker. This read is also background work. */
    public static void refresh(MinecraftServer server,UUID id){var owner=owner(server);io(owner,()->JEDNotificationStore.read(owner.world,id)).thenAccept(value->accept(owner,value))
        .exceptionally(error->{LogUtils.getLogger().error("JED completion {} remains on disk but could not be queued for delivery",id,error);return null;});}
    public static void publishQuietly(MinecraftServer server,JEDOperation.Kind kind,Collection<ResourceLocation> ids){publish(server,kind,ids).exceptionally(error->{LogUtils.getLogger().error("JED operation completed but its notification could not be persisted; inspect the outbox IO failure",error);return null;});}
    private static void accept(Owner owner,JEDNotificationStore.Pending value){
        thread(owner.server);if(owner.stopping)return;var old=owner.entries.get(value.operation.id());
        if(old==null||!old.ready&&value.ready||value.round>old.round||value.round==old.round&&value.acknowledged.containsAll(old.acknowledged)){
            owner.entries.put(value.operation.id(),value);
            if(!owner.delivered.contains(value.operation.id())&&!owner.working.contains(value.operation.id())&&owner.queued.add(value.operation.id()))owner.queue.addLast(value.operation.id());
        }
    }
    private static <T> CompletableFuture<T> io(Owner owner,Supplier<T> task){
        var result=new CompletableFuture<T>();if(owner.stopping){result.completeExceptionally(new CancellationException("JED notifications are stopping"));return result;}
        try{owner.worker.execute(()->{
            try{long began=System.nanoTime();T value;try{value=task.get();}finally{owner.maximumIoNanos=Math.max(owner.maximumIoNanos,System.nanoTime()-began);}owner.server.execute(()->{if(owner.stopping)result.completeExceptionally(new CancellationException("Server stopped after persisting notification work"));else result.complete(value);});}
            catch(Throwable error){owner.server.execute(()->result.completeExceptionally(error));}
        });}catch(RejectedExecutionException error){result.completeExceptionally(error);}return result;
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){
        var owner=owner(event.getServer());io(owner,()->JEDNotificationStore.readAll(owner.world)).whenComplete((values,error)->{
            if(error!=null){LogUtils.getLogger().error("JED notification outbox could not be opened; refusing to silently discard completions",error);owner.server.halt(false);return;}
            values.forEach(value->accept(owner,value));owner.initialized=true;
        });
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event){
        var owner=OWNERS.get(event.getServer());if(owner==null||owner.stopping||!owner.initialized||JEDDimensionManager.isBusy(owner.server))return;
        try{JEDDefinitionHooks.requireIdle(owner.server);}catch(JEDDefinitionException busy){return;}
        long deadline=System.nanoTime()+2_000_000L;int count=0;
        while(!owner.queue.isEmpty()&&count++<4&&System.nanoTime()<deadline){
            if(JEDDimensionManager.isBusy(owner.server))return;
            var id=owner.queue.removeFirst();owner.queued.remove(id);var value=owner.entries.get(id);
            if(value==null||owner.working.contains(id)||owner.delivered.contains(id))continue;
            if(value.complete()){retire(owner,value);continue;}
            if(!value.ready){
                if(value.initialSession.equals(owner.session))continue;
                boolean completed=value.recovery.equals("registered")&&value.operation.dimensions().keySet().stream().allMatch(d->JEDDefinitionManager.state(owner.server).prepared().catalogue().creations().containsKey(d))
                    ||value.recovery.equals("reactivated")&&value.operation.dimensions().keySet().stream().allMatch(d->JEDDimensionLifecycle.enabled(owner.server,d));
                owner.working.add(id);
                if(completed){var operation=snapshot(owner.server,value.operation.kind(),id,value.operation.dimensions().keySet());update(owner,id,io(owner,()->JEDNotificationStore.ready(owner.world,id,operation)));}
                else update(owner,id,io(owner,()->{JEDNotificationStore.remove(owner.world,id);return null;}));
                continue;
            }
            if(!owner.session.equals(value.attemptSession)){owner.working.add(id);update(owner,id,io(owner,()->JEDNotificationStore.attempt(owner.world,id,owner.session)));continue;}
            deliver(owner,value);
        }
    }
    private static void update(Owner owner,UUID id,CompletableFuture<JEDNotificationStore.Pending> result){result.whenComplete((value,error)->{
        owner.working.remove(id);if(error!=null){owner.delivered.add(id);LogUtils.getLogger().error("JED notification IO failed; record retained for next startup: {}",id,error);}
        else if(value==null)owner.entries.remove(id);else{accept(owner,value);if(value.complete()){owner.working.add(id);update(owner,id,io(owner,()->{JEDNotificationStore.remove(owner.world,id);return null;}));}}
    });}
    private static void deliver(Owner owner,JEDNotificationStore.Pending value){
        var id=value.operation.id();owner.delivered.add(id);boolean replay=!value.initialSession.equals(owner.session);
        for(var consumer:value.consumers.stream().sorted().toList()){
            if(value.acknowledged.contains(consumer))continue;var type=JEDNotificationRegistry.consumers().get(consumer);
            if(type==null){LogUtils.getLogger().warn("JED completion {} retained for missing notification addon {}",id,consumer);continue;}
            var delivery=new JEDNotificationDelivery(owner.server,value.operation,consumer,replay,value.round==2,()->{
                var result=io(owner,()->JEDNotificationStore.acknowledge(owner.world,id,value.round,consumer));
                result.thenAccept(saved->{owner.receipts++;accept(owner,saved);retire(owner,saved);});return result.thenApply(saved->null);
            });
            long began=System.nanoTime();try{type.handler().accept(delivery);owner.deliveries++;}
            catch(Throwable error){LogUtils.getLogger().error("JED notification addon {} failed operation {}; any unacknowledged delivery remains pending",consumer,id,error);}
            finally{owner.maximumHandlerNanos=Math.max(owner.maximumHandlerNanos,System.nanoTime()-began);}
        }
        // An incomplete record is advertised again in each session, including the command bridge.
        boolean observed=false,bridged=false;
        if(!observed)try{NeoForge.EVENT_BUS.post(new JEDOperationCompletedEvent(owner.server,value.operation,replay));observed=true;}catch(Throwable error){LogUtils.getLogger().error("JED completion observer failed for {}; registered consumers retain independent receipts",id,error);}
        if(!bridged)try{JEDBroadcastCommand.emit(owner.server,value.operation);bridged=true;}catch(Throwable error){LogUtils.getLogger().error("JED command-event notification failed for {}",id,error);}
        boolean observedResult=observed,bridgedResult=bridged;
        io(owner,()->JEDNotificationStore.observations(owner.world,id,value.round,observedResult,bridgedResult)).whenComplete((saved,error)->{
            if(error!=null)LogUtils.getLogger().error("JED observation receipt could not be persisted for {}",id,error);else{accept(owner,saved);retire(owner,saved);}
        });
    }
    private static void retire(Owner owner,JEDNotificationStore.Pending value){if(value.complete()&&owner.working.add(value.operation.id()))update(owner,value.operation.id(),io(owner,()->{JEDNotificationStore.remove(owner.world,value.operation.id());return null;}));}
    public static Map<String,Object> diagnostics(MinecraftServer server){thread(server);var owner=OWNERS.get(server);if(owner==null)return Map.of("pending",0);return Map.of("pending",owner.entries.size(),"deliveries",owner.deliveries,"acknowledgements",owner.receipts,"maximum_handler_ns",owner.maximumHandlerNanos,"maximum_io_ns",owner.maximumIoNanos,"session",owner.session.toString());}
    /** Barrier for administration/tests: receipts submitted before this call have reached durable storage. */
    public static CompletableFuture<Void> flush(MinecraftServer server){return io(owner(server),()->null);}
    public static JEDNotificationStore.Pending closedReset(ResourceLocation id,UUID token){
        var operation=new JEDOperation(token,JEDOperation.Kind.RESET,Instant.now(),0,Map.of(id,new JEDOperation.DimensionState(true,true,false)));
        return new JEDNotificationStore.Pending(operation,UUID.randomUUID(),consumers(JEDOperation.Kind.RESET),true,"",1,0,0,Set.of());
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void stopping(ServerStoppingEvent event){
        var owner=OWNERS.get(event.getServer());if(owner==null)return;owner.stopping=true;owner.worker.shutdown();
        try{if(!owner.worker.awaitTermination(5,TimeUnit.SECONDS)){owner.worker.shutdownNow();JEDShutdownCleanup.inhibit(event.getServer());LogUtils.getLogger().error("JED notification IO did not stop in time; temporary-save cleanup inhibited to retain its outbox");}}
        catch(InterruptedException error){Thread.currentThread().interrupt();owner.worker.shutdownNow();JEDShutdownCleanup.inhibit(event.getServer());}
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){OWNERS.remove(event.getServer());}
    private static void thread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("JED notification state requires the server thread");}
    private JEDNotifications(){}
}
