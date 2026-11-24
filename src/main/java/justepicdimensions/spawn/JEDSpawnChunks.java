package justepicdimensions.spawn;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionLifecycle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.Unit;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Owns only JED tickets. Terrain is prepared before one native region ticket takes over. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDSpawnChunks {
    private static final TicketType<UUID> PREPARE = TicketType.create("jed_spawn_preparation_area", Comparator.<UUID>naturalOrder());
    private static final TicketType<UUID> RETAIN = TicketType.create("jed_spawn_retention", Comparator.<UUID>naturalOrder());
    private static final Map<MinecraftServer, Map<ResourceLocation, Policy>> POLICIES = new IdentityHashMap<>();
    private static final Map<ServerLevel, Owner> OWNERS = new LinkedHashMap<>();
    private static final Set<Job> DRAINING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<MinecraftServer> STOPPING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<MinecraftServer, Metrics> METRICS = new IdentityHashMap<>();
    private record Policy(boolean enabled, Integer radius, JEDSpawnChunksDefinition.Activity activity) {}
    private record Target(ChunkPos center, int radius, JEDSpawnChunksDefinition.Activity activity) {
        boolean sameArea(Target other) { return other != null && center.equals(other.center) && radius == other.radius; }
    }
    private record Region(Target target, UUID token) {}
    /** Lazy coordinates keep large radii from allocating/sorting an entire area in one server slice. */
    private static final class Area implements Iterator<ChunkPos> {
        final ChunkPos center; int x, z, dx, dz = -1, remaining;
        Area(Target target) { center = target.center; int width = 2 * (target.radius + 1) + 1; remaining = width * width; }
        public boolean hasNext() { return remaining > 0; }
        public ChunkPos next() {
            if (!hasNext()) throw new NoSuchElementException(); var chunk = new ChunkPos(center.x + x, center.z + z);
            // Reuse the spawn column and its generation dependencies before expanding the square.
            if (x == z || x < 0 && x == -z || x > 0 && x == 1 - z) { int old = dx; dx = -dz; dz = old; }
            x += dx; z += dz; remaining--; return chunk;
        }
    }
    private static final class Metrics {
        long slices, maxNanos, maxRawNanos, completionNanos, maxCompletionNanos, submissions, maxSubmissions, maxInFlight, maxReconcileNanos, maxDispatchNanos, acquisitions, maxAcquisitions;
        long tickNanos, tickCompletionNanos, postAcquisitions; int cursor, tickAcquisitions, tickNumber = Integer.MIN_VALUE;
    }
    private static final class Owner {
        final ServerLevel level;
        Region active;
        Job work;
        Owner(ServerLevel level) { this.level = level; }
    }

    public static void publish(MinecraftServer server, Map<ResourceLocation, JEDDimensionDefinition> definitions) {
        thread(server);
        var policies = new HashMap<ResourceLocation, Policy>();
        definitions.forEach((id, definition) -> {
            boolean explicit = definition.source().value().getAsJsonObject().has("spawn_chunks");
            boolean keep = definition.lifecycle().keepLoaded();
            if (explicit || keep) {
                var settings = definition.spawnChunks();
                policies.put(id, new Policy(settings.retains(keep, explicit), settings.radius(), settings.activity()));
            }
        });
        var previous = POLICIES.put(server, Map.copyOf(policies));
        for (var level : server.getAllLevels()) {
            boolean wasManaged = previous != null && previous.containsKey(level.dimension().location());
            if (managed(level)) begin(level);
            else if (wasManaged) release(level, "Spawn-chunk policy removed");
            if (managed(level) != wasManaged) updateNative(level);
        }
    }
    private static Policy policy(ServerLevel level) { return POLICIES.getOrDefault(level.getServer(), Map.of()).get(level.dimension().location()); }
    public static boolean managed(ServerLevel level) { return policy(level) != null; }
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
    /** Native owns its START ticket only for unmanaged levels. This also removes a former native ticket on adoption. */
    public static int nativeRadius(int nativeValue, ServerLevel level) { return managed(level) || PUBLISHING.get() && level.dimension() != Level.OVERWORLD ? 0 : nativeValue; }
    /**
     * Publishes a JED-selected world spawn through the native setter. Vanilla only ever retains the Overworld's spawn
     * area, so a secondary dimension's spawn is published without the native START ticket; retention there remains
     * the explicit {@code keep_loaded}/{@code spawn_chunks} policy's job.
     */
    public static void publishSpawn(ServerLevel level, net.minecraft.core.BlockPos pos, float angle) {
        boolean previous = PUBLISHING.get(); PUBLISHING.set(true);
        try { level.setDefaultSpawnPos(pos, angle); }
        finally { PUBLISHING.set(previous); }
    }
    /** Managed Overworld preparation advances after startup rather than blocking Minecraft's prepareLevels loop. */
    public static int startupRadius(int nativeValue, MinecraftServer server) { return nativeRadius(nativeValue, server.overworld()); }
    public static boolean continuous(boolean nativeForced, ServerLevel level) {
        var owner = OWNERS.get(level);
        return nativeForced || owner != null && owner.active != null && owner.active.target.activity == JEDSpawnChunksDefinition.Activity.CONTINUOUS;
    }
    public static void spawnChanged(ServerLevel level) {
        var owner = OWNERS.get(level);
        if (owner != null) reconcile(owner);
    }
    private static void updateNative(ServerLevel level) {
        var spawn = new ChunkPos(worldSpawn(level));
        if (level.lastSpawnChunkRadius > 1) level.getChunkSource().removeRegionTicket(TicketType.START, spawn, level.lastSpawnChunkRadius, Unit.INSTANCE);
        // Returning a secondary dimension to native behavior means no spawn area, as in vanilla.
        int radius = level.dimension() == Level.OVERWORLD ? nativeRadius(level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS), level) + 1 : 1;
        if (radius > 1) level.getChunkSource().addRegionTicket(TicketType.START, spawn, radius, Unit.INSTANCE);
        level.lastSpawnChunkRadius = radius;
    }
    /** Match the native spawn setter's stored position, including JED's independent spawn store. */
    private static net.minecraft.core.BlockPos worldSpawn(ServerLevel level) { return JEDSpawnController.spawnPosition(level.getLevelData().getSpawnPos(), level); }
    private static void begin(ServerLevel level) {
        if (!managed(level) || STOPPING.contains(level.getServer()) || level.getServer().levels.get(level.dimension()) != level || !JEDDimensionLifecycle.available(level.getServer(), level.dimension().location())) return;
        var owner = OWNERS.computeIfAbsent(level, Owner::new);
        reconcile(owner);
    }
    public static CompletableFuture<Void> ready(ServerLevel level) {
        thread(level.getServer());
        if (STOPPING.contains(level.getServer())) return CompletableFuture.failedFuture(new CancellationException("Server stopping"));
        if (level.getServer().levels.get(level.dimension()) != level) return CompletableFuture.failedFuture(new JEDDefinitionException("Spawn retention requested for a closed or replaced native level: " + level.dimension().location()));
        begin(level);
        var owner = OWNERS.get(level);
        return owner == null || owner.work == null ? CompletableFuture.completedFuture(null) : owner.work.result;
    }
    private static Target target(ServerLevel level) {
        var policy = policy(level);
        int radius = !policy.enabled ? 0 : policy.radius == null ? level.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) : policy.radius;
        var search = JEDSpawnController.search(level);
        // Fixed coordinates are known before their terrain/platform is ready. Prepare that exact
        // region concurrently, but never commit it or release arrival gates before positionReady.
        var center = search != null && search.mode().equals("fixed") && JEDSpawnStore.get(level).position == null
                ? new ChunkPos(search.x() >> 4, search.z() >> 4) : new ChunkPos(worldSpawn(level));
        return new Target(center, radius, policy.activity);
    }
    private static void reconcile(Owner owner) {
        var level = owner.level;
        if (OWNERS.get(level) != owner || level.getServer().levels.get(level.dimension()) != level || STOPPING.contains(level.getServer()) || !managed(level)) return;
        var position = JEDSpawnController.positionReady(level);
        if (owner.work != null) owner.work.position = position;
        if (!position.isDone()) {
            var search = JEDSpawnController.search(level);
            var known = search != null && search.mode().equals("fixed") ? target(level) : null;
            if (known != null && known.radius > 0 && (owner.active == null || !known.sameArea(owner.active.target))) {
                if (owner.work == null) owner.work = new Job(owner, position, known);
                else if (owner.work.target == null && !owner.work.result.isDone()) owner.work.initialize(known);
                else if (!known.equals(owner.work.target)) { owner.work.cancel("Fixed spawn target changed"); owner.work = new Job(owner, position, known); }
            } else if (owner.work == null) owner.work = new Job(owner, position, null);
            else if (owner.work.target != null) { owner.work.cancel("Spawn position is being resolved again"); owner.work = new Job(owner, position, null); }
            return;
        }
        try { position.join(); }
        catch (Throwable error) {
            if (owner.work == null) owner.work = new Job(owner, position, null);
            owner.work.fail(error); return;
        }
        var desired = target(level);
        if (desired.radius == 0 || owner.active != null && desired.sameArea(owner.active.target)) {
            if (owner.work != null && owner.work.committed && desired.radius > 0 && desired.sameArea(owner.work.target)) {
                owner.work.target = desired; owner.active = new Region(desired, owner.active.token); return;
            }
            if (owner.work != null) { owner.work.cancel("Spawn retention superseded"); owner.work = null; }
            if (desired.radius == 0) removeActive(owner);
            else owner.active = new Region(desired, owner.active.token);
            return;
        }
        if (owner.work != null && owner.work.target == null && !owner.work.result.isDone()) { owner.work.initialize(desired); return; }
        if (owner.work != null && desired.equals(owner.work.target)) return;
        if (owner.work != null) owner.work.cancel("Spawn position or retention settings changed");
        owner.work = new Job(owner, position, desired);
    }
    private static void removeActive(Owner owner) {
        var old = owner.active; owner.active = null;
        if (old != null) owner.level.getChunkSource().removeRegionTicket(RETAIN, old.target.center, old.target.radius + 1, old.token);
    }
    /** Automatic unloading/bulk unloading must not defeat a configured retained spawn area. */
    public static boolean retaining(ServerLevel level) { var owner = OWNERS.get(level); return owner != null && owner.active != null; }
    /** Direct lifecycle operations may release our own tickets, but never tickets owned by native commands or another mod. */
    public static boolean foreignTickets(ServerLevel level) {
        for (var set : level.getChunkSource().chunkMap.getDistanceManager().tickets.values())
            for (var ticket : set) if (ticket.getType() != PREPARE && ticket.getType() != RETAIN) return true;
        return false;
    }
    public static void releaseForClose(ServerLevel level) { thread(level.getServer()); release(level, "Dimension closing"); }
    private static void release(ServerLevel level, String reason) {
        var owner = OWNERS.remove(level);
        if (owner == null) return;
        if (owner.work != null) owner.work.cancel(reason);
        if (owner.work != null && STOPPING.contains(level.getServer()) && owner.work.inFlight == 0) owner.work.cleanup(Long.MAX_VALUE);
        removeActive(owner);
    }
    public static JsonObject diagnostics(ServerLevel level) {
        var json = new JsonObject(); var policy = policy(level); var owner = OWNERS.get(level);
        json.addProperty("managed", policy != null);
        if (policy != null) { var target = target(level); json.addProperty("enabled", target.radius > 0); json.addProperty("radius", target.radius); json.addProperty("activity", target.activity.name().toLowerCase(Locale.ROOT)); }
        json.addProperty("retained", owner != null && owner.active != null);
        json.addProperty("preparing", owner != null && owner.work != null && !owner.work.result.isDone());
        json.addProperty("published_tickets_waiting", owner == null || owner.work == null ? 0 : owner.work.waiting.size());
        json.addProperty("native_requests_in_flight", owner == null || owner.work == null ? 0 : owner.work.inFlight);
        if (owner != null && owner.active != null) { json.addProperty("center_x", owner.active.target.center.x); json.addProperty("center_z", owner.active.target.center.z); }
        if (owner != null && owner.work != null && owner.work.failure != null) json.addProperty("error", owner.work.failure.toString());
        return json;
    }
    public static Map<String, Long> timings(MinecraftServer server) {
        var metric = METRICS.get(server);
        return metric == null ? Map.of() : Map.ofEntries(Map.entry("slices", metric.slices), Map.entry("max_slice_ns", metric.maxNanos), Map.entry("max_raw_slice_ns", metric.maxRawNanos), Map.entry("max_completion_ns", metric.maxCompletionNanos), Map.entry("max_reconcile_ns", metric.maxReconcileNanos), Map.entry("max_dispatch_ns", metric.maxDispatchNanos), Map.entry("submissions", metric.submissions), Map.entry("max_submissions_per_tick", metric.maxSubmissions), Map.entry("max_in_flight", metric.maxInFlight), Map.entry("native_requests", metric.acquisitions), Map.entry("max_native_requests_per_tick", metric.maxAcquisitions), Map.entry("post_native_requests", metric.postAcquisitions));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void loaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && managed(level)) { begin(level); updateNative(level); }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event) {
        var server = event.getServer(); if (STOPPING.contains(server)) return;
        long start = System.nanoTime(), deadline = start + 2_000_000;
        var metrics = METRICS.computeIfAbsent(server, ignored -> new Metrics());
        metrics.tickNanos = 0; metrics.tickAcquisitions = 0; metrics.tickCompletionNanos = 0; metrics.tickNumber = server.getTickCount();
        long completionBefore = metrics.completionNanos;
        for (var job : List.copyOf(DRAINING)) {
            if (System.nanoTime() >= deadline) break;
            if (job.owner.level.getServer() == server && job.inFlight == 0) job.cleanup(deadline);
        }
        var owners = OWNERS.values().stream().filter(owner -> owner.level.getServer() == server).toList();
        long dispatchStart = System.nanoTime(), dispatchCompletionBefore = metrics.completionNanos;
        int issued = 0, inFlight = DRAINING.stream().filter(job -> job.owner.level.getServer() == server).mapToInt(Job::outstanding).sum();
        inFlight += owners.stream().filter(owner -> owner.work != null && !owner.work.terminal).mapToInt(owner -> owner.work.outstanding()).sum();
        if (!owners.isEmpty()) {
            int first = Math.floorMod(metrics.cursor++, owners.size());
            for (int index = 0; index < owners.size() && System.nanoTime() < deadline; index++) {
                var owner = owners.get((first + index) % owners.size());
                long reconcileStart = System.nanoTime(), reconcileCompletionBefore = metrics.completionNanos;
                reconcile(owner);
                metrics.maxReconcileNanos = Math.max(metrics.maxReconcileNanos, System.nanoTime() - reconcileStart - (metrics.completionNanos - reconcileCompletionBefore));
                var job = owner.work;
                if (job == null || job.result.isDone() || job.target == null) continue;
                try {
                    if (job.committed) { job.finishCommit(deadline); continue; }
                    // Fallback for tickets whose holders were not eligible in the previous Post pass.
                    int outstandingBefore = job.outstanding();
                    job.acquire(deadline);
                    inFlight += job.outstanding() - outstandingBefore;
                    while (!job.result.isDone() && issued < 4 && inFlight < 4 && job.chunks.hasNext() && System.nanoTime() < deadline) { job.submit(job.chunks.next()); issued++; inFlight++; }
                    if (!job.terminal && !job.result.isDone() && !job.chunks.hasNext() && job.outstanding() == 0 && job.position.isDone()) { job.position.join(); job.commit(); job.finishCommit(deadline); }
                } catch (Throwable error) { job.fail(error); }
            }
        }
        metrics.maxDispatchNanos = Math.max(metrics.maxDispatchNanos, System.nanoTime() - dispatchStart - (metrics.completionNanos - dispatchCompletionBefore));
        finishSlice(metrics, start, completionBefore);
        metrics.slices++; metrics.submissions += issued;
        metrics.maxSubmissions = Math.max(metrics.maxSubmissions, issued); metrics.maxInFlight = Math.max(metrics.maxInFlight, inFlight);
    }
    /** Subscribe after native ticket propagation, before another tick can retain already-finished work. */
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void afterTick(ServerTickEvent.Post event) {
        var server = event.getServer(); var metrics = METRICS.get(server);
        if (!server.isRunning() || STOPPING.contains(server) || metrics == null || metrics.tickNumber != server.getTickCount() || metrics.tickNanos >= 2_000_000 || metrics.tickAcquisitions >= 4) return;
        long start = System.nanoTime(), deadline = start + 2_000_000 - metrics.tickNanos, before = metrics.acquisitions, completionBefore = metrics.completionNanos;
        var owners = OWNERS.values().stream().filter(owner -> owner.level.getServer() == server).toList();
        int first = owners.isEmpty() ? 0 : Math.floorMod(metrics.cursor - 1, owners.size());
        for (int index = 0; index < owners.size() && System.nanoTime() < deadline && metrics.tickAcquisitions < 4; index++) {
            var owner = owners.get((first + index) % owners.size()); var level = owner.level; var job = owner.work;
            if (job == null || job.terminal || job.result.isDone() || job.committed || job.target == null || job.waiting.isEmpty()
                    || OWNERS.get(level) != owner || server.levels.get(level.dimension()) != level || !managed(level)
                    || !JEDDimensionLifecycle.available(server, level.dimension().location()) || !job.target.equals(target(level))) continue;
            try { job.acquire(deadline); } catch (Throwable error) { job.fail(error); }
        }
        metrics.postAcquisitions += metrics.acquisitions - before;
        finishSlice(metrics, start, completionBefore);
    }
    private static void finishSlice(Metrics metrics, long start, long completionBefore) {
        metrics.tickNanos += System.nanoTime() - start;
        metrics.tickCompletionNanos += metrics.completionNanos - completionBefore;
        metrics.maxRawNanos = Math.max(metrics.maxRawNanos, metrics.tickNanos);
        metrics.maxNanos = Math.max(metrics.maxNanos, metrics.tickNanos - metrics.tickCompletionNanos);
        metrics.maxAcquisitions = Math.max(metrics.maxAcquisitions, metrics.tickAcquisitions);
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event) { if (event.getLevel() instanceof ServerLevel level) release(level, "Dimension unloaded"); }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        STOPPING.add(event.getServer());
        for (var level : List.copyOf(OWNERS.keySet())) if (level.getServer() == event.getServer()) release(level, "Server stopping");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        var server = event.getServer(); POLICIES.remove(server); STOPPING.remove(server); METRICS.remove(server);
        DRAINING.removeIf(job -> job.owner.level.getServer() == server);
    }
    private static final class Job {
        final Owner owner; final UUID token = UUID.randomUUID();
        final CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> position;
        final Set<ChunkPos> tickets = new LinkedHashSet<>(); final AutoCloseable pin;
        final Deque<ChunkPos> waiting = new ArrayDeque<>();
        Target target; Iterator<ChunkPos> chunks, cleanup; int inFlight; boolean committed, terminal, closed; Throwable failure;
        Job(Owner owner, CompletableFuture<Void> position, Target target) {
            this.owner = owner; this.position = position;
            pin = JEDDimensionLifecycle.pinPending(owner.level.getServer(), owner.level.dimension().location(), owner.level.dimension().location());
            if (target != null) initialize(target);
        }
        void initialize(Target target) {
            this.target = target;
            // Native START radius r keeps the inner (2r-1)^2 entity-ticking area plus its native ticking/full rings.
            chunks = new Area(target);
        }
        void submit(ChunkPos chunk) {
            tickets.add(chunk); owner.level.getChunkSource().addRegionTicket(PREPARE, chunk, 0, token); waiting.addLast(chunk);
        }
        int outstanding() { return inFlight + waiting.size(); }
        void acquire(long deadline) {
            var level = owner.level; var source = level.getChunkSource(); var metrics = METRICS.get(level.getServer());
            for (int remaining = waiting.size(); remaining > 0 && !terminal && metrics.tickAcquisitions < 4 && System.nanoTime() < deadline; remaining--) {
                var chunk = waiting.removeFirst();
                if (!source.hasChunk(chunk.x, chunk.z)) { waiting.addLast(chunk); continue; }
                inFlight++; metrics.acquisitions++; metrics.tickAcquisitions++;
                // The visible holder already has our FULL ticket. create=false cannot add UNKNOWN
                // tickets or synchronously call runDistanceManagerUpdates. All work stays on-server.
                try { source.getChunkFutureMainThread(chunk.x, chunk.z, ChunkStatus.FULL, false).whenCompleteAsync((loaded, error) -> {
                    inFlight--;
                    if (!terminal && (error != null || !loaded.isSuccess())) fail(error != null ? error : new JEDDefinitionException("Native spawn-area chunk failed: " + chunk));
                    if (terminal && inFlight == 0) DRAINING.add(this);
                }, level.getServer());
                } catch (Throwable error) { inFlight--; throw error; }
            }
        }
        void commit() {
            var level = owner.level;
            level.getChunkSource().addRegionTicket(RETAIN, target.center, target.radius + 1, token);
            removeActive(owner); owner.active = new Region(target, token); committed = true;
        }
        void finishCommit(long deadline) {
            if (!cleanup(deadline)) return;
            if (owner.work == this) owner.work = null;
            terminal = true; complete(null);
        }
        void cancel(String reason) { fail(new CancellationException(reason)); }
        void fail(Throwable error) {
            if (terminal) return; terminal = true; failure = error; waiting.clear();
            if (inFlight == 0 && tickets.isEmpty()) cleanup(Long.MAX_VALUE); else DRAINING.add(this);
            if (!(error instanceof CancellationException)) LogUtils.getLogger().error("JED spawn area preparation failed for {}", owner.level.dimension().location(), error);
            complete(error);
        }
        void complete(Throwable error) {
            long start = System.nanoTime();
            try { if (error == null) result.complete(null); else result.completeExceptionally(error); }
            finally { var metrics = METRICS.computeIfAbsent(owner.level.getServer(), ignored -> new Metrics()); long elapsed = System.nanoTime() - start; metrics.completionNanos += elapsed; metrics.maxCompletionNanos = Math.max(metrics.maxCompletionNanos, elapsed); }
        }
        boolean cleanup(long deadline) {
            if (closed) return true;
            if (inFlight != 0) return false;
            if (cleanup == null) cleanup = tickets.iterator();
            while (cleanup.hasNext() && System.nanoTime() < deadline) { var chunk = cleanup.next(); owner.level.getChunkSource().removeRegionTicket(PREPARE, chunk, 0, token); cleanup.remove(); }
            if (cleanup.hasNext()) return false;
            closed = true; DRAINING.remove(this);
            try { pin.close(); } catch (Exception error) { LogUtils.getLogger().error("JED spawn-area lease cleanup failed", error); }
            return true;
        }
    }
    private static void thread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Spawn retention requires the server thread"); }
    private JEDSpawnChunks() {}
}
