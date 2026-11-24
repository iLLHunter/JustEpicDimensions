package justepicdimensions.dimension;

import com.mojang.logging.LogUtils;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.Executor;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.*;

/** Server-thread lifecycle owner. Registration is durable and independent of loading an instance. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDimensionManager {
    private record UnloadFailure(ResourceLocation dimension,String stage){}
    private static final Map<MinecraftServer,UnloadFailure> UNLOAD_FAILURES=new IdentityHashMap<>();
    private static net.minecraft.network.chat.Component recoveryWarning(UnloadFailure failure){
        return justepicdimensions.JEDText.tr("justepicdimensions.unload.failure",net.minecraft.network.chat.Component.literal(failure.dimension().toString()),
                justepicdimensions.JEDText.tr("justepicdimensions.unload.failure_stage."+failure.stage())).copy().withStyle(net.minecraft.ChatFormatting.RED);
    }
    private static void failedClose(MinecraftServer server,ResourceLocation id,String stage,Throwable error){
        RECOVERY_REQUIRED.add(server);var failure=new UnloadFailure(id,stage);UNLOAD_FAILURES.putIfAbsent(server,failure);
        try{server.getPlayerList().broadcastSystemMessage(recoveryWarning(failure),false);}
        catch(RuntimeException notification){if(notification!=error)error.addSuppressed(notification);}
        LogUtils.getLogger().error("JED failed to unload {} during {}. Further JED dimension lifecycle changes are blocked. Shut down and restart this server. Saving after this failure cannot be guaranteed.",id,stage,error);
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void warnRecoveryJoin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event){
        if(event.getEntity() instanceof ServerPlayer player){var failure=UNLOAD_FAILURES.get(player.server);if(failure!=null)player.sendSystemMessage(recoveryWarning(failure));}
    }

    private static final Set<MinecraftServer> RUNNING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<MinecraftServer> BUSY = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<MinecraftServer> RECOVERY_REQUIRED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<ServerLevel, BorderChangeListener> BORDERS = new IdentityHashMap<>();
    private static final MethodHandle INPUTS;
    static {
        try { INPUTS = MethodHandles.publicLookup().findVirtual(MinecraftServer.class, "jed$levelInputs", MethodType.methodType(Object[].class)); }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    public static java.util.concurrent.CompletableFuture<Void> create(MinecraftServer server, ResourceLocation id) {
        return JEDDimensionCreation.create(server, id);
    }
    static void checkCreation(MinecraftServer server) {
        thread(server); requireHealthy(server);
        justepicdimensions.admin.JEDAdminUnloader.requireIdle(server);
        if(justepicdimensions.builder.JEDBuilderService.busy(server))throw new JEDDefinitionException("A builder operation is in progress");
        if (!RUNNING.contains(server) || BUSY.contains(server) || JEDDimensionLifecycle.busy(server)) throw new JEDDefinitionException("Dimension lifecycle is not ready or another operation is in progress");
    }
    static void recoveryRequired(MinecraftServer server) { RECOVERY_REQUIRED.add(server); }
    static void publish(MinecraftServer server, ResourceLocation id, JEDPreparedDefinitions next,
        Map<ResourceLocation,JEDDimensionDefinition> definitions, JEDRegistryTransaction transaction, JEDDimensionSync.Serialized wire, JEDCreationTimings timings) {
        checkCreation(server); BUSY.add(server);
        try {
            try {
                JEDDimensionSync.beginPublication(server);
                try { timings.measure("registry_publish_callbacks", transaction::publish); }
                catch (Throwable error) {
                    // A callback may already have changed another mod's caches. Never recycle these IDs or overwrite the saved intent.
                    RECOVERY_REQUIRED.add(server);
                    LogUtils.getLogger().error("JED creation {} is durably reserved, but a native registry callback failed. Stop this server and inspect the callback error before retrying.", id, error);
                    throw new JEDDefinitionException("Native callback failed after durable creation of " + id + "; IDs retained, restart recovery required", error);
                }
                timings.measure("definition_commit", () -> JEDDefinitionManager.commit(server, next, definitions));
                timings.measure("network_publish", () -> JEDDimensionSync.commit(server, wire));
            } catch (Throwable error) {
                // Include failures while preparing connections, and preserve the original recovery error.
                try { JEDDimensionSync.abortPublication(server, error); }
                catch (Throwable abort) { error.addSuppressed(abort); }
                throw error;
            }
            justepicdimensions.definition.JEDInfoLog.info(server,"JED dimension {} registered and saved; native level can now be loaded", id);
        } finally { BUSY.remove(server); }
    }
    public static ServerLevel load(MinecraftServer server, ResourceLocation id) {
        thread(server); var key = ResourceKey.create(Registries.DIMENSION, id);
        if (!JEDDimensionLifecycle.accessible(server,id)) throw new JEDDefinitionException("Dimension is disabled or completing a lifecycle operation: " + id);
        var existing = server.levels.get(key); if (existing != null) return existing;
        requireHealthy(server);
        justepicdimensions.instance.JEDInstanceController.requireLoad(server,id);
        if (!RUNNING.contains(server) || !BUSY.add(server)) throw new JEDDefinitionException("Cannot recursively load a dimension during another lifecycle operation: " + id);
        try {
            var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(id);
            if (stem == null) throw new JEDDefinitionException("Dimension is not registered: " + id);
            if (key == Level.OVERWORLD) throw new JEDDefinitionException("The Overworld must already be loaded");
            var inputs = (Object[]) INPUTS.invoke(server);
            var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
            var level = new ServerLevel(server, (Executor) inputs[0], (LevelStorageSource.LevelStorageAccess) inputs[1], data, key, stem,
                new ChunkProgressListener() { public void updateSpawnPos(ChunkPos pos) {} public void onStatusChange(ChunkPos pos, ChunkStatus status) {} public void start() {} public void stop() {} },
                server.getWorldData().isDebugWorld(), BiomeManager.obfuscateSeed(server.getWorldData().worldGenOptions().seed()), List.of(), false, server.overworld().getRandomSequences());
            if(!justepicdimensions.world.JEDWorldControl.options(server).borders()) {
            var border = new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
            level.getWorldBorder().applySettings(server.overworld().getWorldBorder().createSettings());
            server.overworld().getWorldBorder().addListener(border); BORDERS.put(level, border);
            }
            JEDServerLevels.register(server, key, level);
            justepicdimensions.pregen.JEDPregenerationHooks.forget(level);
            JEDDimensionLifecycle.loaded(server,id);
            JEDPersistentChunks.begin(level);
            NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
            justepicdimensions.notification.JEDNotifications.publishQuietly(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.LOADED,List.of(id));
            return level;
        } catch (RuntimeException | Error error) { throw error; }
        catch (Throwable error) { throw new JEDDefinitionException("Failed to load " + id, error); }
        finally { BUSY.remove(server); }
    }
    public static boolean loadAtStartup(MinecraftServer server, ResourceKey<LevelStem> key) {
        if (!JEDDimensionLifecycle.enabled(server, key.location())) return false;
        var state = JEDDefinitionManager.state(server); var definition = state.definitions().get(key.location());
        if (definition != null && definition.source().value().getAsJsonObject().has("lifecycle"))
            return definition.lifecycle().loadOnStart() || definition.lifecycle().keepLoaded();
        // Runtime creations retain the legacy register-without-loading default when reopening.
        return !state.prepared().catalogue().creations().containsKey(key.location());
    }
    public static ServerLevel lookup(ServerLevel nativeLevel, MinecraftServer server, ResourceKey<Level> key) {
        if (server.isSameThread() && RUNNING.contains(server) && !JEDDimensionLifecycle.accessible(server, key.location())) return null;
        if (nativeLevel != null || !server.isSameThread() || !RUNNING.contains(server) || BUSY.contains(server)) return nativeLevel;
        if (!server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(key.location())) return null;
        return load(server, key.location());
    }
    public static void unload(MinecraftServer server, ResourceLocation id) {
        unloadAsync(server,id).whenComplete((ignored,error) -> { if (error != null) LogUtils.getLogger().error("JED unload/reset failed for {}",id,error); });
    }
    public static java.util.concurrent.CompletableFuture<Void> unloadAsync(MinecraftServer server, ResourceLocation id) {
        justepicdimensions.admin.JEDAdminUnloader.requireIdle(server);
        thread(server); var definition = JEDDefinitionManager.state(server).definitions().get(id);
        if (definition != null && definition.lifecycle().temporary()) return JEDDimensionLifecycle.operate(server,id,JEDDimensionLifecycle.Action.RESET,false);
        if (JEDDimensionLifecycle.busy(server)) throw new JEDDefinitionException("Another dimension lifecycle operation is in progress");
        boolean loaded=server.levelKeys().contains(ResourceKey.create(Registries.DIMENSION,id));
        closeForLifecycle(server,id);
        if(loaded)justepicdimensions.notification.JEDNotifications.publishQuietly(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.UNLOADED,List.of(id));
        // Preserve the synchronous ordinary-close API. Notification receipts never gate native work.
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    static void closeForLifecycle(MinecraftServer server, ResourceLocation id) {
        justepicdimensions.admin.JEDAdminUnloader.requireClose(server,id);
        thread(server); requireHealthy(server); var key = ResourceKey.create(Registries.DIMENSION, id);
        if (key == Level.OVERWORLD) throw new JEDDefinitionException("The Overworld cannot be unloaded");
        var definition = JEDDefinitionManager.state(server).definitions().get(id);
        if (definition != null && definition.lifecycle().keepLoaded()) throw new JEDDefinitionException(id + " is configured keep_loaded");
        if (JEDDimensionLifecycle.pinned(server,id)) throw new JEDDefinitionException(id + " is busy with " + JEDDimensionLifecycle.pinReason(server, id));
        var level = server.levels.get(key); if (level == null) return;
        if (!level.players().isEmpty() || !level.getForcedChunks().isEmpty() || justepicdimensions.spawn.JEDSpawnChunks.foreignTickets(level))
            throw new JEDDefinitionException(id + " still has players or chunk tickets; unload was rejected");
        if(justepicdimensions.pregen.JEDPregenerationController.retaining(level))throw new JEDDefinitionException("Chunky still retains this dimension; pause/cancel it and wait for pending chunks");
        if (!BUSY.add(server)) throw new JEDDefinitionException("Another dimension operation is in progress");
        String stage="save";
        try {
            justepicdimensions.spawn.JEDSpawnChunks.releaseForClose(level);
            level.save(null, true, false);
            stage="event";NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
            stage="close";JEDServerLevels.remove(server, key);
            var border = BORDERS.remove(level); if (border != null) server.overworld().getWorldBorder().removeListener(border);
            level.close(); // Level.close owns its chunk source; closing it twice hangs its already-closed IO worker.
            justepicdimensions.pregen.JEDPregenerationHooks.forget(level);
            JEDDimensionLifecycle.loaded(server,id);
        } catch (java.io.IOException | RuntimeException error) {
            failedClose(server,id,stage,error);
            throw new JEDDefinitionException("Failed to unload "+id+" during "+stage+"; shut down and restart the server; further JED lifecycle changes are blocked",error);
        }
        finally { BUSY.remove(server); }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) { RUNNING.add(event.getServer()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        JEDDimensionCreation.stop(event.getServer());
        RUNNING.remove(event.getServer()); BUSY.remove(event.getServer()); RECOVERY_REQUIRED.remove(event.getServer()); UNLOAD_FAILURES.remove(event.getServer()); BORDERS.keySet().removeIf(level -> level.getServer() == event.getServer());
        JEDCreationTimings.stopped(event.getServer());
    }
    public static boolean isBusy(MinecraftServer server) { return justepicdimensions.bundle.JEDBundleService.busy(server) || BUSY.contains(server) || JEDDimensionCreation.reserved(server) || JEDDimensionLifecycle.busy(server) || justepicdimensions.builder.JEDBuilderService.busy(server) || justepicdimensions.admin.JEDAdminUnloader.busy(server); }
    public static boolean recoveryRequiredForInspection(MinecraftServer server){thread(server);return RECOVERY_REQUIRED.contains(server);}
    private static void requireHealthy(MinecraftServer server) {
        if (RECOVERY_REQUIRED.contains(server)) throw new JEDDefinitionException("A JED dimension operation failed; shut down and restart the server before further lifecycle changes");
    }
    private static void thread(MinecraftServer server) { if (!server.isSameThread()) throw new IllegalStateException("Dimension changes require the server thread"); }
    private JEDDimensionManager() {}
}
