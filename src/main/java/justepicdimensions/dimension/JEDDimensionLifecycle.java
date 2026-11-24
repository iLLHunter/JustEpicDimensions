package justepicdimensions.dimension;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-thread lifecycle coordinator. Detached IO is serialized by one worker per save. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDimensionLifecycle {
    public enum Action { UNREGISTER, RESET, DELETE }
    private static final ResourceLocation OVERWORLD = Level.OVERWORLD.location();
    private static final Map<MinecraftServer,Owner> OWNERS = new IdentityHashMap<>();
    private static final Map<DistanceManager,ServerLevel> TICKET_LEVELS = new ConcurrentHashMap<>();
    private static final class Owner {
        final MinecraftServer server;
        final Path world;
        final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED lifecycle IO").factory());
        final Map<ResourceLocation,Integer> pins = new HashMap<>();
        final Map<ResourceLocation,Long> idleSince = new HashMap<>();
        final Set<ResourceLocation> quiescing = new HashSet<>();
        final Set<UUID> relocated = new HashSet<>();
        JEDLifecycleStore store;
        boolean busy, stopping, recovery;
        /** This owner began a registry publication that has not yet committed. */
        boolean publishing;
        volatile boolean durable;
        volatile long workerNs;
        ResourceLocation target;
        ResourceLocation commandDimension;
        int cursor;
        long nextCheck;
        Map<String,Object> timings = Map.of();
        Runnable afterDrain;
        CompletableFuture<Void> drainingResult;
        Owner(MinecraftServer server) {
            this.server = server; world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(); store = JEDLifecycleStore.read(world);
        }
    }
    private static Owner owner(MinecraftServer server) { thread(server); return OWNERS.computeIfAbsent(server, Owner::new); }
    public static boolean enabled(MinecraftServer server, ResourceLocation id, Set<ResourceLocation> fallback) {
        var owner = OWNERS.get(server); return owner==null ? !fallback.contains(id)
            : !owner.store.disabled().contains(id)&&!JEDDefinitionManager.state(server).prepared().configuredDisabled().contains(id);
    }
    public static boolean enabled(MinecraftServer server, ResourceLocation id) {
        return enabled(server, id, JEDDefinitionManager.state(server).prepared().disabled());
    }
    public static boolean available(MinecraftServer server, ResourceLocation id) {
        var owner = OWNERS.get(server); return enabled(server, id) && (owner == null || !owner.quiescing.contains(id));
    }
    static boolean accessible(MinecraftServer server, ResourceLocation id) {
        var owner = OWNERS.get(server); return available(server,id) || owner != null && id.equals(owner.commandDimension) && enabled(server,id);
    }
    public static void requireAvailable(MinecraftServer server, ResourceLocation id) {
        if (!available(server, id)) throw new JEDDefinitionException("Dimension is disabled or completing a lifecycle operation: " + id);
    }
    public static Set<ResourceLocation> activeDimensions(MinecraftServer server) {
        var result = new TreeSet<>(server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet()); result.removeIf(id -> !enabled(server, id)); return Set.copyOf(result);
    }
    static Set<ResourceLocation> protectedDimensions(MinecraftServer server) {
        var result = new HashSet<>(server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet());
        result.addAll(owner(server).store.disabled()); server.levelKeys().forEach(key -> result.add(key.location())); return Set.copyOf(result);
    }
    public static boolean busy(MinecraftServer server) { var owner = OWNERS.get(server); return owner != null && (owner.busy || owner.recovery); }
    public static boolean pinned(MinecraftServer server, ResourceLocation id) { var owner = OWNERS.get(server); return owner != null && owner.pins.getOrDefault(id, 0) > 0; }
    /** Detached administrative view; never reads journals or constructs a level. */
    public static Set<ResourceLocation> retiredDimensions(MinecraftServer server){thread(server);var owner=OWNERS.get(server);return owner==null?Set.of():owner.store.disabled();}
    public static Map<ResourceLocation,JEDSourceDocument> retainedStems(MinecraftServer server){thread(server);var owner=OWNERS.get(server);return owner==null?Map.of():owner.store.retainedStems();}
    public static JsonObject diagnostics(MinecraftServer server){
        thread(server);var value=new JsonObject();var owner=OWNERS.get(server);value.addProperty("busy",busy(server));value.addProperty("recovery_required",(owner!=null&&owner.recovery)||JEDDimensionManager.recoveryRequiredForInspection(server));
        if(owner!=null){value.addProperty("durable",owner.durable);if(owner.target!=null)value.addProperty("target",owner.target.toString());var pins=new JsonObject();owner.pins.forEach((id,count)->pins.addProperty(id.toString(),count));value.add("pins",pins);var quiescing=new JsonArray();owner.quiescing.stream().sorted().forEach(id->quiescing.add(id.toString()));value.add("quiescing",quiescing);var operation=owner.store.operation();if(operation!=null){var summary=new JsonObject();for(String key:List.of("dimension","action","phase","token"))if(operation.has(key))summary.add(key,operation.get(key));value.add("operation",summary);}value.add("last_timings",new Gson().toJsonTree(owner.timings));}
        return value;
    }
    static void loaded(MinecraftServer server, ResourceLocation id) {
        var owner = OWNERS.get(server); if (owner != null) owner.idleSince.remove(id);
        var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));if(level!=null)bindIdle(level);
    }
    private static void bindIdle(ServerLevel level){TICKET_LEVELS.put(level.getChunkSource().chunkMap.getDistanceManager(),level);}
    private static void idleActivity(ServerLevel level){
        var server=level.getServer();if(!server.isSameThread()){server.execute(()->idleActivity(level));return;}
        var owner=OWNERS.get(server);if(owner!=null&&!owner.stopping)owner.idleSince.remove(level.dimension().location());
    }
    /** Called by the native bridge before ticket refresh/removal. A no-op removal is not activity. */
    public static void ticketActivity(DistanceManager manager,long position,Ticket<?> ticket,boolean adding){
        var level=TICKET_LEVELS.get(manager);if(level==null)return;
        if(!level.getServer().isSameThread()){idleActivity(level);return;}
        if(!adding){var tickets=manager.tickets.get(position);if(tickets==null||!tickets.contains(ticket))return;}
        idleActivity(level);
    }
    public static void playerActivity(DistanceManager manager){var level=TICKET_LEVELS.get(manager);if(level!=null)idleActivity(level);}
    @SubscribeEvent public static void idleLevelLoaded(LevelEvent.Load event){if(event.getLevel() instanceof ServerLevel level)bindIdle(level);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void idleLevelUnloaded(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level)TICKET_LEVELS.remove(level.getChunkSource().chunkMap.getDistanceManager());}
    @SubscribeEvent public static void idleServerStopped(ServerStoppedEvent event){TICKET_LEVELS.values().removeIf(level->level.getServer()==event.getServer());}
    /** Reconcile only effective policy changes; unrelated reloads leave countdowns intact. */
    public static void publishIdlePolicy(MinecraftServer server,JEDDefinitionManager.State previous,JEDDefinitionManager.State next){
        var owner=OWNERS.get(server);if(owner==null)return;
        if(previous==null){owner.idleSince.clear();return;}
        var ids=new HashSet<>(owner.idleSince.keySet());server.levelKeys().forEach(key->ids.add(key.location()));
        for(var id:ids){
            var old=previous.prepared().idleUnloadOptions().resolve(previous.definitions().get(id));
            var current=next.prepared().idleUnloadOptions().resolve(next.definitions().get(id));
            if(!old.equals(current))owner.idleSince.remove(id);
        }
    }
    /** Addons and bulk world operations hold this lease from planning until their final native work is complete. */
    public static AutoCloseable pin(MinecraftServer server, ResourceLocation id) {
        requireAvailable(server, id); return hold(server,id);
    }
    public static AutoCloseable pinPending(MinecraftServer server, ResourceLocation source, ResourceLocation target) {
        if (target != null) requireAvailable(server,target);
        var from = hold(server,source); var to = target == null || target.equals(source) ? null : hold(server,target);
        return () -> { try { from.close(); } finally { if (to != null) to.close(); } };
    }
    private static AutoCloseable hold(MinecraftServer server, ResourceLocation id) {
        var owner = owner(server); owner.pins.merge(id, 1, Integer::sum);
        return new AutoCloseable() {
            boolean closed;
            public void close() {
                if (!server.isSameThread()) { server.execute(this::close); return; }
                if (closed) return; closed = true; owner.pins.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
                owner.idleSince.remove(id);
            }
        };
    }
    /** What holds a pinned dimension open, naming a JED pregeneration job when that is the holder. */
    public static String pinReason(MinecraftServer server, ResourceLocation id) {
        var job = justepicdimensions.pregen.JEDPregenerationController.blocking(server, id);
        return job != null ? job : "pending arrivals or active operations";
    }
    public static String blocker(MinecraftServer server, ResourceLocation id) {
        var definition = JEDDefinitionManager.state(server).definitions().get(id);
        if (definition != null && definition.lifecycle().keepLoaded()) return "keep_loaded";
        if (pinned(server, id)) return pinReason(server, id);
        var level = server.levels.get(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return null;
        if (justepicdimensions.pregen.JEDPregenerationController.retaining(level)) return "Chunky generation or pending chunk work";
        if (!level.getForcedChunks().isEmpty()) return "chunk tickets";
        if (!level.players().isEmpty()) return "players";
        // A player on the death screen has left level.players() but still belongs to this level until respawn.
        for (var player : server.getPlayerList().getPlayers()) if (player.serverLevel() == level) return "players";
        if (justepicdimensions.spawn.JEDSpawnChunks.foreignTickets(level)) return "chunk tickets";
        return null;
    }
    private static void begin(Owner owner) {
        JEDDimensionManager.checkCreation(owner.server); JEDDefinitionHooks.requireIdle(owner.server);
        if (owner.busy || owner.recovery) throw new JEDDefinitionException("A lifecycle transaction is already active or requires restart recovery");
        JEDDimensionCreation.invalidate(owner.server); owner.durable = false; owner.target = null; owner.workerNs = 0; owner.busy = true;
    }
    private static ResourceLocation fallback(MinecraftServer server, ResourceLocation id) {
        var definition = JEDDefinitionManager.state(server).definitions().get(id);
        var owner = OWNERS.get(server); var record = owner == null ? null : owner.store.disabledEntry(id);
        var target = record != null ? ResourceLocation.parse(record.get("fallback").getAsString()) : definition == null ? OVERWORLD : definition.lifecycle().fallback();
        if (target.equals(id) || !available(server, target) || !server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(target)) target = OVERWORLD;
        return target;
    }
    private static JsonObject relocation(ResourceLocation from, ResourceLocation to, boolean position, boolean respawn) {
        var record = new JsonObject(); record.addProperty("from", from.toString()); record.addProperty("fallback", to.toString());
        record.addProperty("position", position); record.addProperty("respawn", respawn); return record;
    }
    public static CompletableFuture<Void> operate(MinecraftServer server, ResourceLocation id, Action action, boolean relocate) {
        return operate(server,id,action,relocate,null);
    }
    static CompletableFuture<Void> removeDefinition(MinecraftServer server,ResourceLocation id,boolean relocate,JsonObject removal){
        return operate(server,id,Action.UNREGISTER,relocate,removal);
    }
    public static CompletableFuture<Void> operateScheduled(MinecraftServer server,ResourceLocation id,boolean relocate,UUID token,JsonObject metadata){
        justepicdimensions.schedule.JEDScheduleStore.validateMetadata(metadata,id);return operate(server,id,Action.RESET,relocate,null,token,metadata.deepCopy());
    }
    private static CompletableFuture<Void> operate(MinecraftServer server,ResourceLocation id,Action action,boolean relocate,JsonObject removal){return operate(server,id,action,relocate,removal,null,null);}
    private static CompletableFuture<Void> operate(MinecraftServer server,ResourceLocation id,Action action,boolean relocate,JsonObject removal,UUID scheduledToken,JsonObject scheduledMetadata){
        thread(server); if (id.equals(OVERWORLD)) throw new JEDDefinitionException("The running Overworld cannot be reset, unregistered or deleted");
        var owner = owner(server); var current = JEDDefinitionManager.state(server);
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(id);
        if (stem == null && !owner.store.disabled().contains(id)) throw new JEDDefinitionException("Unknown dimension: " + id);
        if (action != Action.DELETE && !enabled(server, id)) throw new JEDDefinitionException("Reactivate this dimension before " + action.name().toLowerCase(Locale.ROOT) + ": " + id);
        var reason = blocker(server, id);
        if (reason != null && !(relocate && reason.equals("players"))) throw new JEDDefinitionException(id + " is pinned by " + reason);
        var online = new ArrayList<>(server.getPlayerList().getPlayers());
        var checkpointReferences=justepicdimensions.spawn.JEDPlayerStateTracker.get(server).references(id);
        if(!relocate&&!checkpointReferences.isEmpty())throw new JEDDefinitionException(id+" has saved JED checkpoints; explicitly request relocation");
        for (var player : online) if (!relocate && (player.serverLevel().dimension().location().equals(id) || player.getRespawnDimension().location().equals(id)))
            throw new JEDDefinitionException("Online player " + player.getUUID() + " references " + id + "; explicitly request relocation");
        ResourceLocation destination = fallback(server, id);
        // Load the safe destination before reserving this transaction or asking a player to move.
        ServerLevel safe = JEDDimensionManager.load(server, destination);
        var definition = current.definitions().get(id); var settings = definition == null ? null : definition.lifecycle();
        Path template = settings == null || settings.template() == null ? null : current.prepared().selection().activeSource().resolve("templates").resolve(settings.template()).toAbsolutePath().normalize();
        var operation = new JsonObject(); operation.addProperty("token", scheduledToken!=null?scheduledToken.toString():removal==null?UUID.randomUUID().toString():removal.get("token").getAsString()); operation.addProperty("dimension", id.toString());
        if(justepicdimensions.instance.JEDInstanceController.automaticRetire(server))operation.addProperty("instance_automatic",true);
        if(removal!=null)operation.add("definition_removal",removal.deepCopy());
        if(scheduledMetadata!=null)operation.add("schedule",scheduledMetadata);
        operation.addProperty("action", action.name().toLowerCase(Locale.ROOT)); operation.addProperty("phase", "RESERVED");
        operation.addProperty("fallback", destination.toString());
        operation.addProperty("relocate",relocate);
        var protectedDimensions = protectedDimensions(server); JEDDimensionFiles.protect(operation,protectedDimensions);
        if (template != null && action == Action.RESET) operation.addProperty("template", template.toString());
        var commands = new JsonArray(); if (settings != null && action != Action.UNREGISTER) settings.commands().forEach(commands::add); operation.add("commands", commands);
        JsonObject disabled = null;
        if (action != Action.RESET) {
            disabled = new JsonObject(); disabled.addProperty("fallback", destination.toString());
            var source = current.prepared().resources().get(JEDLifecycleStore.stem(id));
            var previous = owner.store.disabledEntry(id);
            JsonElement retained = source != null ? source.value() : previous != null ? previous.get("stem")
                : net.minecraft.world.level.dimension.LevelStem.CODEC.encodeStart(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess()), stem).getOrThrow();
            disabled.add("stem", retained.deepCopy());
            if(removal!=null)disabled.addProperty("definition_removed",true);
        }
        var retired = disabled; begin(owner); owner.target = id; owner.quiescing.add(id);
        var result = new CompletableFuture<Void>() { @Override public boolean cancel(boolean interrupt) { return false; } }; long start = System.nanoTime();
        var liveRelocations = new TreeMap<UUID,JsonObject>();
        for(var uuid:checkpointReferences)liveRelocations.put(uuid,relocation(id,destination,false,true));
        for (var player : online) {
            boolean position = player.serverLevel().dimension().location().equals(id), respawn = player.getRespawnDimension().location().equals(id);
            if (position || respawn) liveRelocations.put(player.getUUID(), relocation(id, destination, position, respawn));
        }
        for(var uuid:checkpointReferences)liveRelocations.get(uuid).addProperty("checkpoint_token",justepicdimensions.spawn.JEDPlayerStateTracker.get(server).player(uuid).points.get(id).token());
        background(owner, () -> {
            if(removal!=null)JEDDefinitionRemoval.validate(owner.world,removal,false);
            if (action != Action.UNREGISTER) JEDDimensionFiles.requireDisjoint(owner.world,id,protectedDimensions);
            if (action == Action.RESET) JEDDimensionFiles.validateTemplate(template);
            var references = new TreeMap<>(JEDDimensionFiles.playerReferences(owner.world, id, destination, relocate));liveRelocations.forEach((uuid,record)->references.merge(uuid,record,(a,b)->{var merged=relocation(id,destination,a.get("position").getAsBoolean()||b.get("position").getAsBoolean(),a.get("respawn").getAsBoolean()||b.get("respawn").getAsBoolean());if(b.has("checkpoint_token"))merged.add("checkpoint_token",b.get("checkpoint_token"));else if(a.has("checkpoint_token"))merged.add("checkpoint_token",a.get("checkpoint_token"));return merged;}));
            owner.durable = true; return owner.store.reserve(operation, retired, references);
        }, persisted -> {
            owner.store = persisted;
            for(var uuid:checkpointReferences)justepicdimensions.spawn.JEDPlayerStateTracker.get(server).forget(uuid,id);
            for (var player : server.getPlayerList().getPlayers()) {
                if (!relocate && (player.getRespawnDimension().location().equals(id) || player.serverLevel().dimension().location().equals(id)))
                    throw new JEDDefinitionException("A player referenced this dimension during preparation; operation retained for safe restart recovery");
                if (player.getRespawnDimension().location().equals(id)) player.setRespawnPosition(safe.dimension(), safe.getSharedSpawnPos(), safe.getSharedSpawnAngle(), true, false);
                if (player.serverLevel().dimension().location().equals(id)) {
                    var arrival = justepicdimensions.spawn.JEDSpawnController.arrivalSpawn(player, safe);
                    player.teleportTo(safe, arrival.getX() + .5, arrival.getY(), arrival.getZ() + .5, safe.getSharedSpawnAngle(), 0);
                }
            }
            owner.drainingResult = result; owner.afterDrain = () -> {
            long close = System.nanoTime(); JEDDimensionManager.closeForLifecycle(server, id); long closeNs = System.nanoTime() - close;
            background(owner, () -> JEDDimensionFiles.execute(owner.world, owner.store), moved -> {
                owner.store = moved;
                publishCatalogue(owner, () -> commands(owner, operation, () -> {
                    owner.quiescing.remove(id); owner.busy = false; owner.idleSince.remove(id);
                    owner.timings = Map.of("action", operation.get("action").getAsString(), "dimension", id.toString(), "token", operation.get("token").getAsString(),
                        "close_ns", closeNs, "worker_ns",owner.workerNs, "total_ns", System.nanoTime() - start);
                    justepicdimensions.definition.JEDInfoLog.info(server,"JED {} {} complete; close={}ms, total={}ms, recoverable trash={}", action, id, closeNs / 1e6, (System.nanoTime() - start) / 1e6, operation.get("token").getAsString());
                    if(action==Action.RESET)justepicdimensions.pregen.JEDPregenerationController.reset(server,id);
                    result.complete(null);
                }, result), result);
            }, result);
            };
            drain(owner,result);
        }, result);
        return result;
    }
    private static void publishCatalogue(Owner owner, Runnable done, CompletableFuture<Void> result) {
        var operation=owner.store.operation();
        if(operation!=null&&operation.has("definition_removal")){
            var current=JEDDefinitionManager.state(owner.server);var resources=(JEDDefinitionResources)owner.server.getResourceManager();var lease=resources.lease();
            background(owner,()->{try(lease){return JEDPreparedDefinitions.prepare(resources.rawDatapacks(),current.prepared().selection()).forReload(current.prepared());}catch(Exception error){throw new CompletionException(error);}},candidate->{
                var definitions=candidate.validateBehavior(owner.server.registryAccess());
                beginPublication(owner);JEDDefinitionManager.commit(owner.server,candidate,definitions);
                var prepared=JEDDimensionSync.catalogue(owner.server);
                background(owner,()->JEDDimensionSync.serialize(prepared),wire->{commitPublication(owner,wire);done.run();},result);
            },result);return;
        }
        var state = JEDDefinitionManager.state(owner.server);
        // An open publication (reactivation) must still commit even when the catalogue already matches.
        boolean publish = owner.publishing || JEDDimensionSync.needsCataloguePublication(owner.server);
        if (publish && !owner.publishing) beginPublication(owner);
        JEDDefinitionManager.commit(owner.server, state.prepared().copiesCommitted(), state.definitions());
        if (!publish) { done.run(); return; }
        var prepared = JEDDimensionSync.catalogue(owner.server);
        background(owner, () -> JEDDimensionSync.serialize(prepared), wire -> { commitPublication(owner, wire); done.run(); }, result);
    }
    private static void beginPublication(Owner owner) { owner.publishing = true; JEDDimensionSync.beginPublication(owner.server); }
    private static void commitPublication(Owner owner, JEDDimensionSync.Serialized wire) { JEDDimensionSync.commit(owner.server, wire); owner.publishing = false; }
    private static void commands(Owner owner, JsonObject operation, Runnable done, CompletableFuture<Void> result) {
        boolean claimed = owner.store.operation().get("phase").getAsString().equals("COMMANDS_CLAIMED");
        background(owner, () -> claimed ? owner.store : owner.store.phase("COMMANDS_CLAIMED"), saved -> {
            owner.store = saved;
            owner.commandDimension = ResourceLocation.parse(operation.get("dimension").getAsString());
            try { if (!claimed) for (var value : operation.getAsJsonArray("commands")) {
                try { owner.server.getCommands().performPrefixedCommand(owner.server.createCommandSourceStack(), value.getAsString()); }
                catch (RuntimeException error) { LogUtils.getLogger().error("JED post-deletion command failed; it will not be replayed: {}", value, error); }
            } } finally { owner.commandDimension = null; }
            var kind=switch(operation.get("action").getAsString()){
                case "reset" -> justepicdimensions.api.lifecycle.JEDOperation.Kind.RESET;
                case "delete" -> justepicdimensions.api.lifecycle.JEDOperation.Kind.DELETED;
                case "unregister" -> justepicdimensions.api.lifecycle.JEDOperation.Kind.UNREGISTERED;
                default -> throw new JEDDefinitionException("Unknown lifecycle completion action");
            };
            var notification=justepicdimensions.notification.JEDNotifications.prepare(owner.server,kind,UUID.fromString(operation.get("token").getAsString()),List.of(ResourceLocation.parse(operation.get("dimension").getAsString())));
            justepicdimensions.instance.JEDInstanceController.completed(owner.server,ResourceLocation.parse(operation.get("dimension").getAsString()),Action.valueOf(operation.get("action").getAsString().toUpperCase(Locale.ROOT)),operation.get("token").getAsString(),operation.has("instance_automatic")&&operation.get("instance_automatic").getAsBoolean());
            long completedAt=System.currentTimeMillis();long completedTicks=operation.has("schedule")?justepicdimensions.schedule.JEDScheduleClock.ticks(owner.server):0;
            background(owner, () -> {
                // Outbox first: a crash before journal cleanup resumes the same operation ID.
                justepicdimensions.notification.JEDNotifications.persist(owner.world,notification);
                return operation.has("schedule")?owner.store.completeScheduled(justepicdimensions.schedule.JEDScheduleHistory.finish(owner.world,operation,completedAt,completedTicks)):owner.store.complete();
            }, complete -> { owner.store = complete; done.run();justepicdimensions.notification.JEDNotifications.refresh(owner.server,notification.operation.id()); }, result);
        }, result);
    }
    public static CompletableFuture<Void> reactivate(MinecraftServer server, ResourceLocation id) {
        if(JEDDefinitionManager.state(server).prepared().configuredDisabled().contains(id))
            throw new JEDDefinitionException(id+": disabled by dimension configuration; enable it and restart the world/server before registering it");
        var owner = owner(server); var record = owner.store.disabledEntry(id);
        if (record == null) throw new JEDDefinitionException("Dimension is already active: " + id);
        var current = JEDDefinitionManager.state(server); var source = current.prepared().resources().get(JEDLifecycleStore.stem(id));
        if (source == null || !source.value().equals(record.get("stem"))) throw new JEDDefinitionException("Reactivation requires the unchanged reserved definition: " + id);
        begin(owner); var result = new CompletableFuture<Void>();
        try {
            var registry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
            if (!registry.containsKey(id)) {
                var transaction = new JEDRegistryTransaction(server.registryAccess(), Map.of(JEDLifecycleStore.stem(id), source), new ArrayList<>(RegistryDataLoader.DIMENSION_REGISTRIES));
                owner.durable = true; beginPublication(owner); transaction.publish();
            }
            var notification=justepicdimensions.notification.JEDNotifications.intent(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.REACTIVATED,UUID.randomUUID(),List.of(id),"reactivated");
            background(owner, () -> { owner.durable = true;justepicdimensions.notification.JEDNotifications.persist(owner.world,notification);return owner.store.activate(id); }, activated -> {
                owner.store = activated; publishCatalogue(owner, () -> {
                    owner.busy = false;
                    var completion=justepicdimensions.notification.JEDNotifications.prepare(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.REACTIVATED,notification.operation.id(),List.of(id));
                    justepicdimensions.notification.JEDNotifications.publish(server,completion).whenComplete((ignored,error)->{if(error==null)result.complete(null);else result.completeExceptionally(error);});
                }, result);
            }, result);
        } catch (Throwable error) { fail(owner,result,error); }
        return result;
    }
    public static CompletableFuture<Void> purge(MinecraftServer server, String token) {
        var owner = owner(server); begin(owner); var result = new CompletableFuture<Void>();
        background(owner, () -> { JEDDimensionFiles.purge(owner.world, token); return true; }, ignored -> { owner.busy = false; result.complete(null); }, result);
        return result;
    }
    private static void fail(Owner owner, CompletableFuture<Void> result, Throwable error) {
        if (owner.publishing) {
            // Never leave connected players behind a preparation barrier that no commit will release.
            owner.publishing = false;
            try { JEDDimensionSync.abortPublication(owner.server, error); }
            catch (RuntimeException abort) { error.addSuppressed(abort); }
        }
        // Release or quarantine ownership BEFORE callbacks can synchronously start another transaction.
        if (owner.durable) {
            owner.recovery = true; LogUtils.getLogger().error("JED lifecycle intent retained for restart recovery",error);
        } else { owner.busy = false; if (owner.target != null) { owner.quiescing.remove(owner.target); owner.idleSince.remove(owner.target); } }
        result.completeExceptionally(error);
    }
    private static <T> void background(Owner owner, Supplier<T> task, Consumer<T> done, CompletableFuture<Void> result) {
        try { owner.worker.execute(() -> {
            try {
                if (owner.server.isSameThread()) throw new IllegalStateException("Lifecycle IO ran on server thread");
                long begin = System.nanoTime(); T value;
                try { value = task.get(); } finally { owner.workerNs += System.nanoTime() - begin; }
                owner.server.execute(() -> {
                    if (owner.stopping) { fail(owner,result,new CancellationException("Server stopped; durable lifecycle recovery will resume next start")); return; }
                    try { done.accept(value); } catch (Throwable error) { fail(owner,result,error); }
                });
            } catch (Throwable error) { owner.server.execute(() -> fail(owner,result,error)); }
        }); } catch (RejectedExecutionException error) { fail(owner,result,error); }
    }
    public static Map<String,Object> timings(MinecraftServer server) { return owner(server).timings; }
    private static void drain(Owner owner, CompletableFuture<Void> result) {
        if (owner.afterDrain == null) return;
        var operation = owner.store.operation();
        if (blocker(owner.server, ResourceLocation.parse(operation.get("dimension").getAsString())) != null) return;
        var next = owner.afterDrain; owner.afterDrain = null; owner.drainingResult = null;
        try { next.run(); } catch (Throwable error) { fail(owner,result,error); }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); var owner = OWNERS.get(server); if (owner == null || owner.stopping || owner.recovery) return;
        if (owner.afterDrain != null) { drain(owner,owner.drainingResult); return; }
        if (owner.busy || JEDDimensionManager.recoveryRequiredForInspection(server) || justepicdimensions.admin.JEDAdminUnloader.busy(server)) return;
        long now = System.nanoTime(); if (now < owner.nextCheck) return; owner.nextCheck = now + 1_000_000_000L;
        var levels = new ArrayList<>(server.levelKeys()); if (levels.isEmpty()) return;
        // At most eight loaded dimensions per pass, no chunk enumeration and no IO here.
        for (int checked = 0; checked < Math.min(8, levels.size()); checked++) {
            var key = levels.get(Math.floorMod(owner.cursor++, levels.size())); var id = key.location();
            var state=JEDDefinitionManager.state(server);var policy=state.prepared().idleUnloadOptions().resolve(state.definitions().get(id));
            if (id.equals(OVERWORLD) || !policy.enabled() || !available(server,id)) { owner.idleSince.remove(id); continue; }
            if (justepicdimensions.spawn.JEDSpawnChunks.retaining(server.levels.get(key)) || blocker(server,id) != null) { owner.idleSince.remove(id); continue; }
            long since = owner.idleSince.computeIfAbsent(id, ignored -> now);
            if (now - since >= policy.idleSeconds() * 1_000_000_000L) {
                try { JEDDimensionManager.unload(server,id); }
                catch (RuntimeException error) { owner.idleSince.remove(id); LogUtils.getLogger().warn(JEDDimensionManager.recoveryRequiredForInspection(server)?"JED automatic unloading stopped after failure for {}. Shut down and restart the server: {}":"JED automatic unload postponed for {}: {}",id,error.getMessage()); }
                return;
            }
        }
    }
    /** Exact login lookup bridge; missing/disabled destinations never reuse coordinates from another world. */
    public static ServerLevel loginLevel(MinecraftServer server, ResourceKey<Level> saved, ServerPlayer player) {
        var owner = owner(server); var relocation = owner.store.relocation(player.getUUID(),saved.location());
        var checkpoints=justepicdimensions.spawn.JEDPlayerStateTracker.get(server);var records=checkpoints.player(player.getUUID());
        for(var id:List.copyOf(records.points.keySet())){var route=owner.store.relocation(player.getUUID(),id);if(route!=null&&route.has("checkpoint_token")&&route.get("checkpoint_token").getAsString().equals(records.points.get(id).token())){checkpoints.forget(player.getUUID(),id);owner.relocated.add(player.getUUID());}}
        boolean move = !available(server, saved.location()) || relocation != null && relocation.get("position").getAsBoolean() && relocation.get("from").getAsString().equals(saved.location().toString());
        var key = move ? ResourceKey.create(Registries.DIMENSION, relocation == null ? fallback(server,saved.location()) : ResourceLocation.parse(relocation.get("fallback").getAsString())) : saved;
        var level = available(server,key.location()) ? server.getLevel(key) : null;
        if (level == null) { move = true; level = server.overworld(); }
        if (move) { player.setPos(level.getSharedSpawnPos().getX()+.5, level.getSharedSpawnPos().getY(), level.getSharedSpawnPos().getZ()+.5); player.setDeltaMovement(Vec3.ZERO); player.fallDistance = 0; owner.relocated.add(player.getUUID()); }
        var respawn = player.getRespawnDimension().location();
        var spawnRoute = owner.store.relocation(player.getUUID(),respawn);
        if (!available(server,respawn) || spawnRoute != null && spawnRoute.get("respawn").getAsBoolean()) {
            var safe = server.getLevel(ResourceKey.create(Registries.DIMENSION, spawnRoute == null ? fallback(server,respawn) : ResourceLocation.parse(spawnRoute.get("fallback").getAsString())));
            if (safe == null) safe = server.overworld(); player.setRespawnPosition(safe.dimension(), safe.getSharedSpawnPos(), safe.getSharedSpawnAngle(), true, false); owner.relocated.add(player.getUUID());
        }
        return level;
    }
    public static DimensionTransition respawn(DimensionTransition nativeTransition, ServerPlayer player, DimensionTransition.PostDimensionTransition post) {
        var id = player.getRespawnDimension().location();
        if (available(player.server,id) && available(player.server,nativeTransition.newLevel().dimension().location())) return nativeTransition;
        var safe = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, fallback(player.server,id))); if (safe == null) safe = player.server.overworld();
        player.setRespawnPosition(safe.dimension(), safe.getSharedSpawnPos(), safe.getSharedSpawnAngle(), true, false);
        return new DimensionTransition(safe, Vec3.atBottomCenterOf(safe.getSharedSpawnPos()).add(.5,0,.5), Vec3.ZERO, safe.getSharedSpawnAngle(), 0, post);
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!"verified".equals(System.getProperty("jed.idle.activity.coremod")))throw new IllegalStateException("JED requires the matching Arbitrage player/ticket activity hooks");
        event.getServer().getAllLevels().forEach(JEDDimensionLifecycle::bindIdle);
        resume(owner(event.getServer()));
    }
    private static void resume(Owner owner) {
        if (!owner.store.pending()) { owner.busy = false; return; }
        owner.busy = true; owner.durable = true; var result = new CompletableFuture<Void>();
        background(owner, () -> owner.store.promoteCommands(), saved -> {
            owner.store = saved; var operation = saved.operation();
            publishCatalogue(owner, () -> commands(owner,operation,() -> { result.complete(null); resume(owner); },result),result);
        },result);
    }
    @SubscribeEvent public static void playerSaved(PlayerEvent.SaveToFile event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var owner = OWNERS.get(player.server); if (owner == null || owner.stopping || owner.busy || JEDDimensionCreation.phase(player.server).isPresent() || !owner.relocated.remove(player.getUUID())) return;
        // Native save completed before this event. Serialize journal cleanup with every other file operation.
        var result = new CompletableFuture<Void>(); owner.busy = true; owner.durable = false; owner.target = null;
        background(owner, () -> JEDLifecycleStore.read(owner.world).forgetRelocation(player.getUUID()), saved -> { owner.store = saved; owner.busy = false; result.complete(null); }, result);
        result.whenComplete((ignored,error) -> { if (error != null) { owner.busy = false; owner.relocated.add(player.getUUID()); } });
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        var owner = OWNERS.get(event.getServer()); if (owner == null) return;
        var definition = JEDDefinitionManager.state(owner.server).definitions().get(OVERWORLD);
        var temporary = new ArrayList<JEDShutdownCleanup.Temporary>();
        for (var entry : JEDDefinitionManager.state(owner.server).definitions().values()) if (!entry.id().equals(OVERWORLD) && entry.lifecycle().temporary()
                && owner.server.levelKeys().contains(ResourceKey.create(Registries.DIMENSION,entry.id())) && enabled(owner.server,entry.id())) {
            var settings = entry.lifecycle(); var source = JEDDefinitionManager.state(owner.server).prepared().selection().activeSource();
            temporary.add(new JEDShutdownCleanup.Temporary(entry.id(),settings.template() == null ? null : source.resolve("templates").resolve(settings.template()),settings.fallback(),settings.commands()));
        }
        boolean whole = definition != null && definition.lifecycle().temporary();
        if (whole || !temporary.isEmpty()) JEDShutdownCleanup.arm(owner.server,owner.world,temporary,whole,whole ? definition.lifecycle().commands() : List.of());
        owner.stopping = true; owner.worker.shutdown();
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { OWNERS.remove(event.getServer()); }
    private static void thread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Dimension lifecycle requires the server thread"); }
    private JEDDimensionLifecycle() {}
}
