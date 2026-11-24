package justepicdimensions.definition;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.lighting.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import com.mojang.logging.LogUtils;

/** Per-server committed definition set; other controllers consume this same resolved state as they are ported. */
@EventBusSubscriber(modid = JustEpicDimensions.ID)
public final class JEDDefinitionManager {
    public record State(long revision, JEDPreparedDefinitions prepared, Map<ResourceLocation, JEDDimensionDefinition> definitions,
                         Map<ResourceLocation, JEDClientLightPolicy> lightPolicies, Map<ResourceLocation,JEDBrightnessTable> brightnessTables) {
        public State { definitions = Map.copyOf(definitions); lightPolicies = Map.copyOf(lightPolicies); brightnessTables=Map.copyOf(brightnessTables); }
    }
    private static final Map<MinecraftServer, State> SERVERS = new IdentityHashMap<>();

    @SubscribeEvent public static void starting(ServerAboutToStartEvent event) {
        var server = event.getServer();
        JEDPreparedDefinitions candidate;
        if (server.getResourceManager() instanceof JEDDefinitionResources resources) {
            candidate = resources.prepared();
            var world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (!world.equals(candidate.selection().world())) {
                // New-world previews precede save selection. Reconcile before any ServerLevel is constructed.
                var selected = JEDPreparedDefinitions.prepare(resources.rawDatapacks(), JEDConfigRepository.select(world));
                selected.requireSameNative(candidate);
                candidate = selected;
            }
        } else throw new JEDDefinitionException("JED startup resource view is missing; worldgen definitions were not resolved before registry loading");
        try { candidate.requireSameNative(JEDDefinitionHooks.image(server.registries())); }
        catch (JEDDefinitionException error) {
            throw new JEDDefinitionException("JED native definitions changed after worldgen decoding. Reload the world-creation datapacks or restore those files before opening this world. " + error.getMessage(), error);
        }
        var decoded=candidate.validate(server.registryAccess());
        JEDConfiguredDimensions.saveBaseline(server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM),candidate.selection().world());
        commit(server, candidate, decoded);
        // Startup has no running ticks or open dimension chunk stores. Runtime copies use the creation worker.
        for (var definition : state(server).definitions().values()) if (!definition.id().equals(net.minecraft.world.level.Level.OVERWORLD.location())
                && !candidate.disabled().contains(definition.id()) && definition.lifecycle().template() != null)
            justepicdimensions.dimension.JEDDimensionFiles.initializeTemplate(candidate.selection().world(),definition.id(),candidate.selection().activeSource().resolve("templates").resolve(definition.lifecycle().template()));
    }

    public static State state(MinecraftServer server) {
        thread(server);
        var state = SERVERS.get(server);
        if (state == null) throw new IllegalStateException("JED definitions are not bound to this server");
        return state;
    }

    public static void commit(MinecraftServer server, JEDPreparedDefinitions candidate, Map<ResourceLocation, JEDDimensionDefinition> definitions) {
        thread(server);
        var old = SERVERS.get(server);
        justepicdimensions.world.JEDWorldControl.validate(server,candidate,definitions);
        justepicdimensions.spawn.JEDSpawnController.validate(server,definitions);
        justepicdimensions.player.JEDPlayerStateController.validate(server,candidate);
        justepicdimensions.world.JEDBorderController.validate(server,candidate,definitions);
        var access=justepicdimensions.access.JEDAccessController.prepare(server,candidate,definitions);
        var mobSpawning=justepicdimensions.mob.JEDMobRulesController.prepare(candidate,definitions);
        var environment=justepicdimensions.environment.JEDEnvironmentController.prepare(server,candidate,definitions);
        var pregeneration=justepicdimensions.pregen.JEDPregenerationController.prepare(server,candidate,definitions);
        var monitoring=justepicdimensions.monitor.JEDMonitoringController.prepare(candidate,definitions);
        var instances=justepicdimensions.instance.JEDInstanceController.prepare(server,candidate,definitions,access.rules());
        var schedules=justepicdimensions.schedule.JEDLifecycleScheduleController.prepare(server,candidate,definitions);
        var policies = new TreeMap<>(JEDPreparedDefinitions.policies(definitions));
        policies.keySet().removeIf(id -> !justepicdimensions.dimension.JEDDimensionLifecycle.enabled(server, id, candidate.disabled()));
        var brightness=new TreeMap<ResourceLocation,JEDBrightnessTable>();
        definitions.forEach((id,definition)->{var table=definition.brightnessTable(); if(table!=null && justepicdimensions.dimension.JEDDimensionLifecycle.enabled(server,id,candidate.disabled())) brightness.put(id,table);});
        var providers=justepicdimensions.provider.JEDProviderController.prepare(server,definitions);
        try(var metadata=justepicdimensions.world.JEDWorldSync.update(server)){
        candidate.selection().commitCopies();
        var next = new State(old == null ? 1 : old.revision() + 1, candidate.copiesCommitted(), definitions, policies, brightness);
        SERVERS.put(server, next);
        justepicdimensions.instance.JEDInstanceController.publish(server,instances,access.rules());
        justepicdimensions.access.JEDAccessController.publish(server,access);
        justepicdimensions.mob.JEDMobRulesController.publish(server,mobSpawning);
        justepicdimensions.environment.JEDEnvironmentController.publish(server,environment);
        justepicdimensions.schedule.JEDLifecycleScheduleController.publish(server,schedules);
        justepicdimensions.pregen.JEDPregenerationController.publish(server,pregeneration);
        justepicdimensions.monitor.JEDMonitoringController.publish(server,monitoring);
        justepicdimensions.dimension.JEDDimensionLifecycle.publishIdlePolicy(server,old,next);
        justepicdimensions.render.JEDRenderController.publish(server,definitions);
        justepicdimensions.structure.JEDStructurePlacer.publish(server);
        justepicdimensions.travel.JEDTeleportService.publish(server,candidate,definitions);
        justepicdimensions.world.JEDWorldControl.commit(server,candidate);
        justepicdimensions.time.JEDTimeController.publish(server,definitions);
        justepicdimensions.weather.JEDWeatherController.publish(server,definitions);
        justepicdimensions.mob.JEDMobSpawnController.publish(server,definitions);
        justepicdimensions.spawn.JEDSpawnChunks.publish(server,definitions);
        justepicdimensions.spawn.JEDSpawnController.publish(server,candidate);
        justepicdimensions.player.JEDPlayerStateController.publish(server,candidate,definitions);
        justepicdimensions.worldgen.JEDWorldgenContext.publish(server,definitions);
        justepicdimensions.end.JEDEndController.publish(server,definitions);
        justepicdimensions.provider.JEDProviderController.publish(server,providers);
        if(old!=null&&!old.prepared().travelOptions().equals(candidate.travelOptions()))justepicdimensions.travel.JEDTravelSync.publish(server);
        JEDBrightnessTables.publish(server,brightness);
        if (old == null || !old.lightPolicies().equals(policies) || !old.brightnessTables().equals(brightness)) JEDLightPolicySync.publish(server, policies,brightness);
        justepicdimensions.dimension.JEDDimensionNamesSync.publish(server);
        var previous = old == null ? Set.of() : new HashSet<>(old.prepared().conflicts());
        candidate.conflicts().stream().filter(conflict -> !previous.contains(conflict))
            .forEach(conflict -> LogUtils.getLogger().info("JED definition conflict: {}", conflict));
        if(candidate.controls().information())LogUtils.getLogger().info("JED definition revision {} applied: {} behavior definitions, {} lighting policies", next.revision(), definitions.size(), policies.size());
        var priorNotices=old==null?Set.of():new HashSet<>(old.prepared().controlNotices());
        candidate.controlNotices().stream().filter(notice->!priorNotices.contains(notice)).forEach(notice->LogUtils.getLogger().warn("JED configuration: {}",notice));
        if(!candidate.pendingRestart().isEmpty())LogUtils.getLogger().warn("JED settings pending world/server restart: {}. Active native definitions and dimension availability were retained.",candidate.pendingRestart());
        metadata.commit();
        }
    }

    public static JEDPreparedDefinitions reloadMainConfig(MinecraftServer server) {
        thread(server);JEDDefinitionHooks.requireIdle(server);
        if(justepicdimensions.dimension.JEDDimensionManager.isBusy(server))throw new JEDDefinitionException("Wait for the active dimension operation before reloading main configuration");
        var previous=state(server);var candidate=previous.prepared().mainConfigReload();
        var definitions=candidate.controls().providers()==previous.prepared().controls().providers()
            ? previous.definitions():candidate.validateBehavior(server.registryAccess());
        commit(server,candidate,definitions);
        justepicdimensions.notification.JEDNotifications.publishQuietly(server,justepicdimensions.api.lifecycle.JEDOperation.Kind.MAIN_CONFIG_RELOADED,justepicdimensions.notification.JEDNotifications.reloadDimensions(server));
        return candidate;
    }

    public static String explain(MinecraftServer server, ResourceLocation id, String property) {
        var definition = state(server).definitions().get(id);
        if (definition == null) throw new JEDDefinitionException(id + ": no JED behavior definition; native behavior applies");
        if (property.equals("lighting.client_light_check")) {
            String value = definition.clientLightPolicy() == null ? "native (unmanaged)" : definition.clientLightPolicy().id();
            String origin = definition.source().origins().get(property);
            if (origin == null) origin = definition.provider() == null ? "no explicit policy or JED provider default"
                : "default for " + definition.provider().type().id() + " (" + definition.source().origin("provider.type") + ")";
            return id + " — " + property + " = " + value + " — " + origin;
        }
        var value = definition.source().value();
        for (String key : property.split("\\.")) {
            if (!value.isJsonObject() || !value.getAsJsonObject().has(key)) throw new JEDDefinitionException(id + ": no configured property " + property);
            value = value.getAsJsonObject().get(key);
        }
        return id + " — " + property + " = " + value + " — " + definition.source().origin(property);
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { SERVERS.remove(event.getServer()); JEDDefinitionHooks.stopped(event.getServer()); }
    static void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("JED definition lifecycle changes must run on the server thread");
    }
    private JEDDefinitionManager() {}
}
