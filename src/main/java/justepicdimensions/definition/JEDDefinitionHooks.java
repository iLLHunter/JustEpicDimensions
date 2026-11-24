package justepicdimensions.definition;

import com.mojang.datafixers.util.Pair;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.*;
import net.minecraft.core.*;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.world.level.storage.*;

/** Exact Java-coremod entry points. Bootstrap transformers refer to these by name without loading them. */
public final class JEDDefinitionHooks {
    private static final Map<PackRepository, Path> SAVES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<RegistryAccess.Frozen, JEDPreparedDefinitions> IMAGES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer, Reload> RELOADS = new IdentityHashMap<>();
    private static final Map<Registry<?>,Set<net.minecraft.resources.ResourceLocation>> RETIRED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Reload {
        JEDPreparedDefinitions candidate;
        Map<net.minecraft.resources.ResourceLocation, JEDDimensionDefinition> definitions;
    }

    public static PackRepository repository(PackRepository repository, Path datapacks) {
        SAVES.put(repository, datapacks.toAbsolutePath().normalize().getParent());
        return repository;
    }

    public static Pair<net.minecraft.world.level.WorldDataConfiguration, CloseableResourceManager> startup(
        Pair<net.minecraft.world.level.WorldDataConfiguration, CloseableResourceManager> pair, WorldLoader.PackConfig config) {
        var resources = pair.getSecond();
        try {
            var selection = JEDConfigRepository.select(SAVES.get(config.packRepository()));
            justepicdimensions.dimension.JEDLifecycleStore.recoverFiles(selection.world());
            var prepared = JEDPreparedDefinitions.prepare(resources, selection);
            var imported=justepicdimensions.structure.JEDTemplateManager.initialize(prepared);
            if(imported!=null){
                resources.close();config.packRepository().reload();
                return new WorldLoader.PackConfig(config.packRepository(),LevelStorageSource.readDataConfig(new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE,imported)),config.safeMode(),config.initMode()).createResourceManager();
            }
            return Pair.of(pair.getFirst(), new JEDDefinitionResources(resources, prepared));
        } catch (Throwable error) { resources.close(); throw error; }
    }

    public static LayeredRegistryAccess<RegistryLayer> registryImage(LayeredRegistryAccess<RegistryLayer> registries, CloseableResourceManager resources) {
        if (!(resources instanceof JEDDefinitionResources resolved)) throw new JEDDefinitionException("JED native registry decoding bypassed the required resource view");
        IMAGES.put(registries.getLayer(RegistryLayer.WORLDGEN), resolved.prepared());
        return registries;
    }
    public static RegistryAccess.Frozen dimensionImage(RegistryAccess.Frozen dimensions, CloseableResourceManager resources) {
        if (resources instanceof JEDDefinitionResources resolved) RETIRED.put(dimensions.registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM), resolved.prepared().disabled());
        return dimensions;
    }
    /** Saved WorldGenSettings can reintroduce stems after datapack filtering; filter the final baked result as well. */
    public static net.minecraft.world.level.levelgen.WorldDimensions.Complete baked(net.minecraft.world.level.levelgen.WorldDimensions.Complete complete,
            Registry<net.minecraft.world.level.dimension.LevelStem> input) {
        complete=JEDConfiguredDimensions.baked(complete,input);
        var retired = RETIRED.get(input); if (retired == null || retired.isEmpty()) return complete;
        var old = complete.dimensions(); var filtered = new MappedRegistry<>(net.minecraft.core.registries.Registries.LEVEL_STEM, old.registryLifecycle());
        old.holders().filter(holder -> !retired.contains(holder.key().location())).forEach(holder -> filtered.register(holder.key(), holder.value(), old.registrationInfo(holder.key()).orElseThrow()));
        return new net.minecraft.world.level.levelgen.WorldDimensions.Complete(filtered.freeze(), complete.specialWorldProperty());
    }

    public static JEDPreparedDefinitions image(LayeredRegistryAccess<RegistryLayer> registries) {
        var image = IMAGES.get(registries.getLayer(RegistryLayer.WORLDGEN));
        if (image == null) throw new JEDDefinitionException("JED has no verified source snapshot for this world's native registry image");
        return image;
    }

    public static void beginReload(MinecraftServer server) {
        JEDDefinitionManager.thread(server);
        if (justepicdimensions.dimension.JEDDimensionManager.isBusy(server)) throw new JEDDefinitionException("A dimension lifecycle operation is in progress; resource reload cannot overlap it");
        if(justepicdimensions.bundle.JEDBundleService.busy(server))throw new JEDDefinitionException("Wait for the current bundle operation");
        if (RELOADS.containsKey(server)) throw new JEDDefinitionException("A JED/server resource reload is already in progress");
        RELOADS.put(server, new Reload());
        justepicdimensions.dimension.JEDDimensionCreation.invalidate(server);
    }

    public static void requireIdle(MinecraftServer server) {
        JEDDefinitionManager.thread(server);
        if (RELOADS.containsKey(server)) throw new JEDDefinitionException("Wait for the current server resource reload before creating a dimension");
    }

    public static CloseableResourceManager reloadResources(CloseableResourceManager resources, MinecraftServer server) {
        JEDDefinitionManager.thread(server);
        try {
            var reload = Objects.requireNonNull(RELOADS.get(server), "JED reload transaction was not started");
            var selection = JEDConfigRepository.select(server.getWorldPath(LevelResource.ROOT));
            var authored=JEDPreparedDefinitions.prepare(resources,selection);authored.requireCreatedFirst(JEDDefinitionManager.state(server).prepared());authored.validateNative(server.registryAccess(),JEDDefinitionManager.state(server).prepared());
            var candidate = authored.forReload(JEDDefinitionManager.state(server).prepared());
            candidate.requireSameNative(JEDDefinitionManager.state(server).prepared());
            var decoded = candidate.validateBehavior(server.registryAccess());
            justepicdimensions.world.JEDWorldControl.validate(server,candidate,decoded);
            justepicdimensions.spawn.JEDSpawnController.validate(server,decoded);
            justepicdimensions.world.JEDBorderController.validate(server,candidate,decoded);
            reload.candidate = candidate; reload.definitions = decoded;
            return new JEDDefinitionResources(resources, candidate);
        } catch (Throwable error) { resources.close(); throw error; }
    }

    public static CompletableFuture<Void> finishReload(CompletableFuture<Void> nativeReload, MinecraftServer server) {
        var transaction = Objects.requireNonNull(RELOADS.get(server), "JED reload transaction was not started");
        var result=new CompletableFuture<Void>(){@Override public boolean cancel(boolean interrupt){return false;}};
        nativeReload.whenComplete((ignored,error)->{
            Throwable failure=error;
            if(failure==null)try{
                if(transaction.candidate==null)throw new JEDDefinitionException("JED required reload resource call site was not invoked");
                JEDDefinitionManager.commit(server,transaction.candidate,transaction.definitions);
            }catch(Throwable commitError){failure=commitError;}
            RELOADS.remove(server,transaction);
            if(failure!=null){result.completeExceptionally(failure);return;}
            try{justepicdimensions.notification.JEDNotifications.publish(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.RELOADED,justepicdimensions.notification.JEDNotifications.reloadDimensions(server))
                .whenComplete((value,publicationError)->{if(publicationError==null)result.complete(null);else result.completeExceptionally(publicationError);});}
            catch(Throwable publicationError){result.completeExceptionally(publicationError);}
        });
        // Native reloadResources pumps server tasks until completion when invoked on its own
        // thread. Our durable notification tail must preserve that same return contract.
        if(server.isSameThread())server.managedBlock(result::isDone);
        return result;
    }

    public static Throwable failedReload(Throwable error, MinecraftServer server) { RELOADS.remove(server); return error; }
    static void stopped(MinecraftServer server) { RELOADS.remove(server); }
    private JEDDefinitionHooks() {}
}
