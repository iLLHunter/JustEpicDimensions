package justepicdimensions.dimension;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** One preparation job per server. Workers do IO/pure data work; native codecs advance only on real server ticks. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDimensionCreation {
    public static final long SERVER_BUDGET_NS = 2_000_000;
    public enum Phase { PREPARING, NATIVE_CODECS, BEHAVIOR, WIRE_CODECS, SERIALIZING, PERSISTING, COMMITTING, DONE }
    private static final Map<MinecraftServer, Owner> OWNERS = new IdentityHashMap<>();
    private static final class Owner {
        final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED dimension preparation").factory());
        final JEDDocumentCache cache = new JEDDocumentCache();
        Job job; long generation; volatile boolean stopping;
    }
    private record Plan(JEDPreparedDefinitions next, Map<ResourceLocation,JEDSourceDocument> additions, String fingerprint, String metadata,
                        JEDRegistryTransaction transaction) {}
    private record Ready(JEDPreparedDefinitions next, Map<ResourceLocation,JEDDimensionDefinition> definitions, JEDDimensionSync.Serialized wire) {}
    private static final class Job {
        final MinecraftServer server;
        final ResourceLocation id;
        final Owner owner;
        final JEDDefinitionManager.State base;
        final JEDDefinitionResources resources;
        final RegistryAccess access;
        final Path config, world;
        final long generation;
        final JEDCreationTimings timings;
        final CompletableFuture<Void> result;
        final justepicdimensions.notification.JEDNotificationStore.Pending notification;
        volatile boolean cancelled, reserved, finished;
        volatile Thread workerThread;
        volatile JEDStagedCopies copies;
        Phase phase = Phase.PREPARING;
        Plan plan; Ready ready;
        JEDDimensionDefinition behavior;
        JEDDimensionSync.Preparation wire;
        Job(MinecraftServer server, ResourceLocation id, Owner owner) {
            this.server = server; this.id = id; this.owner = owner; this.base = JEDDefinitionManager.state(server);
            if (!(server.getResourceManager() instanceof JEDDefinitionResources view)) throw new JEDDefinitionException("Required dimension resource view is missing");
            this.resources = view; access = server.registryAccess(); config = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize(); world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            generation = owner.generation; timings = new JEDCreationTimings(server, id);
            notification=justepicdimensions.notification.JEDNotifications.intent(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.REGISTERED,UUID.randomUUID(),List.of(id),"registered");
            result = new CompletableFuture<>() {
                @Override public boolean cancel(boolean interrupt) {
                    boolean done;
                    synchronized (Job.this) { if (reserved) return false; done = super.cancel(interrupt); }
                    if (done) server.execute(() -> Job.this.cancel("Creation was cancelled")); return done;
                }
            };
        }
        boolean active() { return owner.job == this && !cancelled && !owner.stopping; }
        void checkBase() {
            if (!active() || owner.generation != generation || JEDDefinitionManager.state(server) != base || server.getResourceManager() != resources)
                throw new CancellationException("Dimension preparation became stale after a definition/resource change");
        }
        void checkWorker() { if (cancelled || owner.stopping || Thread.currentThread().isInterrupted()) throw new CancellationException("Dimension preparation was cancelled"); }
        void start() {
            background(true, () -> {
                checkWorker(); var metadata = JEDStagedCopies.metadata(config, world);
                var authored = timings.measure("source_prepare", this::readSources);
                if (!metadata.equals(JEDStagedCopies.metadata(config, world))) throw new JEDDefinitionException("Configuration metadata changed while reading dimension sources");
                var next = timings.measure("select_creation", () -> JEDPreparedDefinitions.creation(base.prepared(), authored, id));
                var additions = new TreeMap<ResourceLocation,JEDSourceDocument>();
                next.resources().forEach((key, document) -> { if (!base.prepared().resources().containsKey(key)) additions.put(key, document); });
                copies = timings.measure("stage_copies", () -> JEDStagedCopies.prepare(next.selection()));
                var fingerprint = timings.measure("fingerprint", authored::fingerprint);
                var transaction = timings.measure("native_plan", () -> JEDRegistryTransaction.incremental(access, additions));
                return new Plan(next, Collections.unmodifiableMap(additions), fingerprint, metadata, transaction);
            }, value -> { plan = value; phase = Phase.NATIVE_CODECS; });
        }
        JEDPreparedDefinitions readSources() {
            checkWorker(); owner.cache.begin();
            try { return JEDPreparedDefinitions.prepare(resources.rawDatapacks(), JEDConfigRepository.select(config, world), owner.cache); }
            finally { owner.cache.end(); }
        }
        void verifyInputs() {
            checkWorker();
            if (!plan.metadata().equals(JEDStagedCopies.metadata(config, world)) || !plan.fingerprint().equals(readSources().fingerprint()))
                throw new JEDDefinitionException("Dimension inputs changed during preparation; retry the creation using the updated files");
            copies.verify(); checkWorker();
        }
        void advance() {
            if (!active()) return;
            try {
                checkBase(); long deadline = System.nanoTime() + SERVER_BUDGET_NS;
                try (var slice = timings.span("server_slice")) {
                    do {
                        switch (phase) {
                            case NATIVE_CODECS -> {
                                timings.measure("native_decode", plan.transaction()::advance);
                                if (plan.transaction().prepared()) phase = Phase.BEHAVIOR;
                            }
                            case BEHAVIOR -> {
                                if (!plan.transaction().containsDimension(id)) throw new JEDDefinitionException("Dimension was excluded by a native condition: " + id);
                                timings.measure("behavior_validate", () -> {
                                    var key = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), JEDDimensionDefinition.DIRECTORY + "/" + id.getPath() + ".json");
                                    var document = plan.next().resources().get(key);
                                    behavior = document == null ? null : JEDDimensionDefinition.decode(id, document, plan.transaction().ops());
                                    if(behavior!=null)justepicdimensions.spawn.JEDSpawnController.validate(behavior,plan.transaction().holder(net.minecraft.core.registries.Registries.LEVEL_STEM,id).orElseThrow().value().type().value());
                                    var definitions=new TreeMap<>(base.definitions());if(behavior!=null)definitions.put(id,behavior);
                                    justepicdimensions.world.JEDWorldControl.validate(server,plan.next(),definitions);
                                    if(behavior!=null&&behavior.instances()!=null){var policies=plan.next().instancePolicies();var policy=policies.get(behavior.instances());if(policy==null)throw new JEDDefinitionException("Unknown instance policy "+behavior.instances());var rules=plan.next().accessRules(plan.transaction().ops());for(var permission:policy.permissions().values())if(permission.rule()!=null&&!rules.containsKey(permission.rule()))throw new JEDDefinitionException("Unknown instance permission rule "+permission.rule());}
                                });
                                wire = timings.measure("wire_plan", () -> JEDDimensionSync.incremental(server, plan.transaction(), id)); phase = Phase.WIRE_CODECS;
                            }
                            case WIRE_CODECS -> {
                                timings.measure("wire_codecs", wire::advance);
                                if (wire.prepared()) {
                                    phase = Phase.SERIALIZING;
                                    background(true, () -> {
                                        var definitions = new TreeMap<>(base.definitions()); if (behavior != null) definitions.put(id, behavior);
                                        var serialized = timings.measure("packet_serialization", () -> JEDDimensionSync.serialize(wire.finish()));
                                        timings.measure("verify_inputs", this::verifyInputs);
                                        return new Ready(plan.next().copiesCommitted(), Map.copyOf(definitions), serialized);
                                    }, value -> { ready = value; reserve(); });
                                }
                            }
                            default -> { return; }
                        }
                    } while (System.nanoTime() < deadline && (phase == Phase.NATIVE_CODECS || phase == Phase.BEHAVIOR || phase == Phase.WIRE_CODECS));
                }
            } catch (Throwable error) { fail(error); }
        }
        void reserve() {
            checkBase();
            justepicdimensions.instance.JEDInstanceController.validateCreation(server,id);
            synchronized (this) {
                if (result.isCancelled()) throw new CancellationException("Creation was cancelled before durable intent");
                reserved = true;
            }
            phase = Phase.PERSISTING;
            background(true, () -> {
                // Definition/reload changes are reserved, but manual file edits still require a last content check.
                timings.measure("verify_inputs_final", this::verifyInputs); checkWorker();
                timings.measure("config_copies", copies::commit);
                timings.measure("notification_intent",()->justepicdimensions.notification.JEDNotifications.persist(world,notification));
                var catalogue = timings.measure("catalogue_write", () -> base.prepared().catalogue().record(new JEDWorldCatalogue.Creation(id, plan.additions())));
                if (behavior != null && behavior.lifecycle().template() != null) timings.measure("template_initialize", () ->
                    JEDDimensionFiles.initializeTemplate(world,id,ready.next().selection().activeSource().resolve("templates").resolve(behavior.lifecycle().template())));
                return ready.next().withCatalogue(catalogue);
            }, prepared -> {
                checkBase(); phase = Phase.COMMITTING;
                timings.measure("server_commit", () -> JEDDimensionManager.publish(server, id, prepared, ready.definitions(), plan.transaction(), ready.wire(), timings));
                finish("created", null);
            });
        }
        <T> void background(boolean leaseResources, java.util.function.Supplier<T> work, java.util.function.Consumer<T> receive) {
            AutoCloseable lease = leaseResources ? resources.lease() : () -> {};
            try {
                owner.worker.execute(() -> {
                    workerThread = Thread.currentThread();
                    try {
                        checkWorker(); T value = work.get(); checkWorker();
                        server.execute(() -> { if (active()) try { receive.accept(value); } catch (Throwable error) { fail(error); } });
                    } catch (Throwable error) { if (!cancelled && !owner.stopping) server.execute(() -> { if (active()) fail(error); }); }
                    finally {
                        if (cancelled || owner.stopping || finished) cleanup();
                        workerThread = null; Thread.interrupted();
                        try { lease.close(); } catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("JED resource lease cleanup failed", error); }
                    }
                });
            } catch (RuntimeException error) { try { lease.close(); } catch (Exception close) { error.addSuppressed(close); } throw error; }
        }
        void cancel(String reason) { if (phase != Phase.DONE) { cancelled = true; var thread = workerThread; if (thread != null) thread.interrupt(); finish("cancelled", new CancellationException(reason)); } }
        void fail(Throwable error) { if (reserved) JEDDimensionManager.recoveryRequired(server); cancelled = true; finish("failed", error); }
        synchronized void cleanup() { var staged = copies; if (staged != null) { copies = null; staged.close(); } }
        void finish(String outcome, Throwable error) {
            if (phase == Phase.DONE) return;
            finished = true; phase = Phase.DONE; if (owner.job == this) owner.job = null;
            timings.finish(server, outcome);
            // Cleanup is IO too. The worker's finally covers cancellation during a running read/write.
            if (workerThread == null) try { owner.worker.execute(this::cleanup); } catch (RejectedExecutionException ignored) {}
            if (error == null) {
                var completion=justepicdimensions.notification.JEDNotifications.prepare(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.REGISTERED,notification.operation.id(),List.of(id));
                justepicdimensions.notification.JEDNotifications.publish(server,completion).whenComplete((ignored,failure)->{if(failure==null)result.complete(null);else result.completeExceptionally(failure);});
            } else result.completeExceptionally(error);
        }
    }
    public static CompletableFuture<Void> create(MinecraftServer server, ResourceLocation id) {
        JEDDimensionManager.checkCreation(server); JEDDefinitionHooks.requireIdle(server);
        var owner = OWNERS.computeIfAbsent(server, ignored -> new Owner());
        if (owner.stopping || owner.job != null) throw new JEDDefinitionException("A dimension creation is already preparing or committing");
        if (server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM).containsKey(id)) throw new JEDDefinitionException("Dimension is already registered: " + id);
        var job = new Job(server, id, owner); owner.job = job;
        try { job.start(); } catch (Throwable error) { job.fail(error); }
        return job.result;
    }
    public static boolean reserved(MinecraftServer server) { var owner = OWNERS.get(server); return owner != null && owner.job != null && owner.job.reserved; }
    public static boolean preparing(MinecraftServer server) { var owner=OWNERS.get(server);return owner!=null&&owner.job!=null; }
    public static Optional<Phase> phase(MinecraftServer server) { var owner = OWNERS.get(server); return owner == null || owner.job == null ? Optional.empty() : Optional.of(owner.job.phase); }
    public static void invalidate(MinecraftServer server) {
        var owner = OWNERS.get(server); if (owner == null) return;
        if (reserved(server)) throw new JEDDefinitionException("Dimension creation is committing; wait before reloading definitions");
        owner.generation++; if (owner.job != null) owner.job.cancel("Preparation invalidated by a server resource reload");
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { var owner = OWNERS.get(event.getServer()); if (owner != null && owner.job != null) owner.job.advance(); }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { stop(event.getServer()); }
    public static void stop(MinecraftServer server) {
        var owner = OWNERS.remove(server); if (owner == null) return;
        owner.stopping = true; if (owner.job != null) owner.job.cancel("Server is stopping"); owner.worker.shutdown();
    }
    private JEDDimensionCreation() {}
}
