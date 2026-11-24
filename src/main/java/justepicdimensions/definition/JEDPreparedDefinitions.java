package justepicdimensions.definition;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.lighting.JEDClientLightPolicy;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** A detached candidate. Preparing or validating it never changes active levels, policy revisions or save files. */
public final class JEDPreparedDefinitions {
    private final JEDConfigRepository.Selection selection;
    private final JEDWorldCatalogue catalogue;
    private final Map<ResourceLocation, JEDSourceDocument> resources;
    private final Map<ResourceLocation, String> nativeContents;
    private final justepicdimensions.structure.JEDStructureAssets structureAssets;
    public justepicdimensions.structure.JEDStructureAssets structureAssets(){return structureAssets;}
    private final List<JEDDefinitionResolver.Conflict> conflicts;
    private final Set<ResourceLocation> disabled;
    private final justepicdimensions.world.JEDWorldOptions worldOptions;
    private final ResourceLocation initialSpawnDimension;
    private final justepicdimensions.player.JEDPlayerOptions playerOptions;
    private final justepicdimensions.travel.JEDTravelOptions travelOptions;
    private final justepicdimensions.dimension.JEDIdleUnloadOptions idleUnloadOptions;
    private final JEDConfigControls controls;
    private final JEDConfigControls constructionControls;
    private final JEDDimensionOrigins origins;
    private final Set<ResourceLocation> configuredDisabled;
    private final Set<String> pendingRestart;
    private final List<String> controlNotices;
    // Retain what was actually read. A Save-only GUI edit must not appear as already active.
    private final java.nio.file.Path mainConfigSource;
    private final JsonObject mainConfigValues;

    private JEDPreparedDefinitions(JEDConfigRepository.Selection selection, JEDWorldCatalogue catalogue,
                                   Map<ResourceLocation, JEDSourceDocument> resources, List<JEDDefinitionResolver.Conflict> conflicts, justepicdimensions.structure.JEDStructureAssets structureAssets,
                                   JEDDimensionOrigins origins, List<String> notices) {
        this.structureAssets=structureAssets; this.selection = selection; this.catalogue = catalogue; this.resources = Map.copyOf(resources); this.conflicts = List.copyOf(conflicts);
        this.mainConfigSource=JEDConfigRepository.mainFile(selection).toAbsolutePath().normalize();
        this.mainConfigValues=justepicdimensions.config.JEDConfigFiles.capture(mainConfigSource,false);
        this.controls=JEDConfigControls.read(selection);this.constructionControls=controls; this.origins=origins; this.pendingRestart=Set.of();
        var messages=new ArrayList<>(notices); var configured=new TreeSet<ResourceLocation>();
        for(var entry:resources.entrySet()) if(entry.getKey().getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/")) {
            var id=JEDRegistryResources.entry(entry.getKey(),JEDDimensionDefinition.DIRECTORY);
            var metadata=JEDDimensionDefinition.metadata(id,entry.getValue());
            if(!metadata.configuredEnabled()) {
                if(controls.disabling()) {
                    if(id.equals(net.minecraft.world.level.Level.OVERWORLD.location()))throw new JEDDefinitionException("JED configuration cannot disable the Overworld");
                    configured.add(id);
                } else messages.add(id+": lifecycle.enabled=false ignored (enableUnregisteringDimensions=false)");
            }
            if(!controls.providers()&&origins.existing().contains(id)&&entry.getValue().value().getAsJsonObject().has("provider"))
                messages.add(id+": JED provider override ignored (enableOverrideWorldProvider=false)");
        }
        this.configuredDisabled=Set.copyOf(configured); this.controlNotices=List.copyOf(messages);
        this.worldOptions=justepicdimensions.world.JEDWorldOptions.read(selection);
        this.initialSpawnDimension=justepicdimensions.spawn.JEDSpawnController.initial(selection);
        this.playerOptions=justepicdimensions.player.JEDPlayerOptions.read(selection);
        this.travelOptions=justepicdimensions.travel.JEDTravelOptions.read(selection);
        this.idleUnloadOptions=justepicdimensions.dimension.JEDIdleUnloadOptions.read(mainConfigValues);
        var disabled=new TreeSet<>(justepicdimensions.dimension.JEDLifecycleStore.read(selection.world()).disabled()); disabled.addAll(configured); this.disabled=Set.copyOf(disabled);
        var nativeContents = new TreeMap<ResourceLocation, String>();
        resources.forEach((id, document) -> { if (JEDRegistryResources.directory(id) != null) nativeContents.put(id, JEDJson.canonical(document.value()).toString()); });
        this.nativeContents = Collections.unmodifiableMap(nativeContents);
    }

    public static JEDPreparedDefinitions prepare(ResourceManager datapacks, JEDConfigRepository.Selection selection) {
        return prepare(datapacks, selection, null);
    }
    public static JEDPreparedDefinitions prepare(ResourceManager datapacks, JEDConfigRepository.Selection selection, JEDDocumentCache cache) {
        return prepare(datapacks,selection,cache,Map.of());
    }
    /** Detached builder candidate; supplied documents replace only their authored JSON files. */
    public static JEDPreparedDefinitions prepare(ResourceManager datapacks, JEDConfigRepository.Selection selection, JEDDocumentCache cache,
            Map<ResourceLocation,JEDSourceDocument> edits) {
        return prepare(datapacks,selection,cache,edits,Map.of());
    }
    public static JEDPreparedDefinitions prepare(ResourceManager datapacks,JEDConfigRepository.Selection selection,JEDDocumentCache cache,Map<ResourceLocation,JEDSourceDocument> edits,Map<ResourceLocation,byte[]> assets) {
        var errors = new ArrayList<String>();
        var pack = new TreeMap<>(JEDDatapackSource.read(datapacks, cache));
        var json = new TreeMap<>(JEDConfigRepository.read(selection, cache));json.putAll(edits);
        var controls=JEDConfigControls.read(selection); var origins=JEDDimensionOrigins.read(datapacks); var notices=new ArrayList<String>();
        var catalogue = JEDWorldCatalogue.read(selection.world());
        var saved = catalogue.resources();
        var lifecycle=justepicdimensions.dimension.JEDLifecycleStore.read(selection.world());
        var retired = lifecycle.retainedStems();
        // A removed override may reveal a different datapack stem. The retired native identity
        // remains reserved; recovery records do not re-enable it or restore authored files.
        var removed=lifecycle.removedDefinitions();
        var savedResources=new TreeMap<>(saved);
        for(var id:removed){
            var stem=justepicdimensions.dimension.JEDLifecycleStore.stem(id);
            savedResources.put(stem,retired.get(stem));pack.put(stem,retired.get(stem));
            savedResources.remove(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),JEDDimensionDefinition.DIRECTORY+"/"+id.getPath()+".json"));
        }
        saved=Map.copyOf(savedResources);
        var retained = new TreeMap<>(saved); retired.forEach(retained::putIfAbsent); saved = Map.copyOf(retained);
        var conflicts = new ArrayList<>(JEDDatapackSource.conflicts(datapacks, pack, cache));
        var keys = new TreeSet<ResourceLocation>(); keys.addAll(pack.keySet()); keys.addAll(json.keySet()); keys.addAll(saved.keySet());
        var result = new TreeMap<ResourceLocation, JEDSourceDocument>();
        var builderSources=new justepicdimensions.builder.JEDBuilderSources(pack,saved,json,origins,controls,notices);
        for (var key : keys) {
            JEDDocumentCache.cancelled();
            try {
                boolean properties = key.getPath().startsWith(JEDDimensionDefinition.DIRECTORY + "/");
                boolean loadout = key.getPath().startsWith(justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY + "/");
                boolean playerGroup = justepicdimensions.player.JEDPlayerCategory.resourceCategory(key).isPresent();
                if (!properties && !loadout && !playerGroup && !justepicdimensions.profile.JEDSettingProfiles.isProfile(key) && !key.getPath().startsWith(justepicdimensions.access.JEDAccessRule.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY+"/")&&!key.getPath().startsWith(justepicdimensions.instance.JEDInstancePolicy.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY+"/") && JEDRegistryResources.directory(key) == null)
                    throw new JEDDefinitionException(key + ": unsupported JSON resource directory; use native registry paths or " + JEDDimensionDefinition.DIRECTORY);
                if(properties&&pack.containsKey(key))checkInput(pack.get(key),key);
                if(properties&&json.containsKey(key)) {
                    var input=JEDJson.object(json.get(key).value(),key.toString());input.remove(justepicdimensions.builder.JEDBuilderSources.HEADER);
                    checkInput(new JEDSourceDocument(input,json.get(key).origin("")),key);
                }
                var resolved = builderSources.resolve(key);
                conflicts.addAll(resolved.conflicts());
                boolean builderEdit=json.containsKey(key)&&json.get(key).value().isJsonObject()&&json.get(key).value().getAsJsonObject().has(justepicdimensions.builder.JEDBuilderSources.HEADER);
                if (JEDRegistryResources.directory(key)!=null && saved.containsKey(key) && !saved.get(key).value().equals(resolved.document().value())&&!builderEdit)
                    throw new JEDDefinitionException(key + ": authored native definition conflicts with its saved runtime creation; use an explicit dimension lifecycle operation");
                if(justepicdimensions.profile.JEDSettingProfiles.isProfile(key)){var profile=builderSources.profiles().profile(JEDRegistryResources.entry(key,justepicdimensions.profile.JEDSettingProfiles.DIRECTORY));conflicts.addAll(profile.conflicts());}
                result.put(key, resolved.document());
            } catch (JEDDefinitionException error) { errors.add(error.getMessage()); }
        }
        fail(errors);
        justepicdimensions.showcase.JEDShowcaseInstaller.optionalIntegrations(result,
            justepicdimensions.config.JEDConfigFiles.capture(JEDConfigRepository.mainFile(selection),false).get("useShowcase").getAsBoolean(),
            net.neoforged.fml.ModList.get()!=null && net.neoforged.fml.ModList.get().isLoaded("chunky"));
        justepicdimensions.instance.JEDInstanceStore.apply(selection.world(),result);
        var requested=new TreeSet<ResourceLocation>();for(var entry:result.entrySet())if(entry.getKey().getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/")){var root=entry.getValue().value().getAsJsonObject();if(root.has("spawn_structure"))requested.add(justepicdimensions.structure.JEDStructureDefinition.decode(root.get("spawn_structure"),entry.getKey().toString()).resource());}
        for(var entry:result.entrySet())if(entry.getKey().getPath().startsWith("worldgen/structure/")&&entry.getValue().value().isJsonObject()){var structure=entry.getValue().value().getAsJsonObject();if(structure.has("type")&&structure.get("type").isJsonPrimitive()&&structure.get("type").getAsString().equals("justepicdimensions:authored")&&structure.has("placement")&&structure.get("placement").isJsonObject()){var asset=structure.getAsJsonObject("placement").get("asset");if(asset!=null&&asset.isJsonPrimitive()&&asset.getAsJsonPrimitive().isString()){var id=ResourceLocation.tryParse(asset.getAsString());if(id!=null&&(id.getPath().endsWith(".nbt")||id.getPath().endsWith(".schem")))requested.add(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),(id.getPath().endsWith(".nbt")?"structure/":"justepicdimensions/schematics/")+id.getPath()));}}}
        return new JEDPreparedDefinitions(selection, catalogue, result, conflicts, justepicdimensions.structure.JEDStructureAssets.capture(datapacks,selection,requested).overrides(assets),origins,notices);
    }

    private static void checkInput(JEDSourceDocument document, ResourceLocation resource) {
        var root = JEDJson.object(document.value(), resource + " (" + document.origin("") + ")");
        JEDJson.keys(root, Set.of("format_version", "profiles", "provider", "lighting", "reset", "lifecycle", "state", "time", "weather", "mobs", "spawn", "spawn_chunks", "sleep", "respawn", "player", "generation", "border", "end", "travel", "display_name", "spawn_structure", "render", "access", "environment", "pregeneration", "instances", "monitoring"), resource.toString());
        if (JEDJson.integer(root.get("format_version"), resource + ".format_version") != 1)
            throw new JEDDefinitionException(resource + ": unsupported format_version; expected 1");
    }

    /** Startup validation. Unlike reload, an invalid monitoring rule or policy is logged and left out at startup, because
     * monitoring is optional and there is no previous configuration to keep. */
    public Map<ResourceLocation, JEDDimensionDefinition> validate(RegistryAccess registries) {
        validateNative(registries, true, Map.of());
        return validateBehavior(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries),
            id -> registries.registryOrThrow(Registries.LEVEL_STEM).containsKey(id), true);
    }
    public Map<ResourceLocation,JEDDimensionDefinition> validateBehavior(RegistryAccess registries) {
        return validateBehavior(net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries),
            id -> registries.registryOrThrow(Registries.LEVEL_STEM).containsKey(id));
    }
    public void validateNative(RegistryAccess registries) { validateNative(registries, false, Map.of()); }
    /** Reload refuses an unknown flat biome only in a new or edited file; an unchanged one was reported at startup. */
    public void validateNative(RegistryAccess registries, JEDPreparedDefinitions active) { validateNative(registries, false, active.nativeContents); }
    /** An existing world whose flat biome is unknown still starts (as plains, like Minecraft); edits are refused. */
    private void validateNative(RegistryAccess registries, boolean startup, Map<ResourceLocation, String> accepted) {
        var errors = new ArrayList<String>();
        JEDRegistryResources.validate(resources, registries, errors);
        for (var problem : JEDRegistryResources.unknownFlatBiomes(resources, registries).entrySet())
            if (startup) com.mojang.logging.LogUtils.getLogger().warn("JED {}. Fix the biome ID in that file.", problem.getValue());
            else if (!Objects.equals(nativeContents.get(problem.getKey()), accepted.get(problem.getKey()))) errors.add(problem.getValue());
        fail(errors);
    }

    public Map<ResourceLocation, JEDDimensionDefinition> validateBehavior(com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops,
            java.util.function.Predicate<ResourceLocation> dimensionExists) {
        return validateBehavior(ops, dimensionExists, false);
    }
    private Map<ResourceLocation, JEDDimensionDefinition> validateBehavior(com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops,
            java.util.function.Predicate<ResourceLocation> dimensionExists, boolean startup) {
        var errors = new ArrayList<String>();
        var definitions = new TreeMap<ResourceLocation, JEDDimensionDefinition>();
        var kits = loadouts(ops);
        var playerGroups = playerGroups(ops);
        var accessRules = accessRules(ops);
        var mobSpawning = mobSpawnPolicies();
        var environment = environmentPolicies(ops);
        var schedules = lifecycleSchedules();
        var pregeneration = pregenerationPolicies();
        var monitoringProblems=new ArrayList<justepicdimensions.monitor.JEDMonitoringPolicy.Problem>();var monitoring=justepicdimensions.monitor.JEDMonitoringPolicy.decodeAll(resources,monitoringProblems);
        for(var problem:monitoringProblems)if(!startup)errors.add(problem.text());
            else com.mojang.logging.LogUtils.getLogger().error("JED {}. Startup continued; {} until you fix the file and run /jed reload.",problem.text(),problem.wholePolicy()?"this policy uses the default settings with no rules":"that rule is off");
        String globalMonitoring=mainConfigValues.get("monitoringPolicy").getAsString();if(!globalMonitoring.isEmpty()&&!monitoring.containsKey(JEDDimensionDefinition.id(globalMonitoring,"monitoringPolicy")))errors.add("Unknown global monitoring policy "+globalMonitoring);
        var instances=instancePolicies();
        for(var policy:instances.values())for(var permission:policy.permissions().values())if(permission.rule()!=null&&!accessRules.containsKey(permission.rule()))errors.add(policy.id()+": unknown instance permission rule "+permission.rule());
        for (var entry : resources.entrySet()) if (entry.getKey().getPath().startsWith(JEDDimensionDefinition.DIRECTORY + "/")) {
            try {
                var id = JEDRegistryResources.entry(entry.getKey(), JEDDimensionDefinition.DIRECTORY);
                var document=entry.getValue();
                if(!controls.providers()&&origins.existing().contains(id)&&document.value().getAsJsonObject().has("provider")) {
                    var value=document.value().getAsJsonObject(); value.remove("provider"); document=new JEDSourceDocument(value,document.origins());
                }
                var definition = JEDDimensionDefinition.decode(id, document, ops);
                if(definition.mobs()!=null&&definition.mobs().policy()!=null&&!mobSpawning.containsKey(definition.mobs().policy()))throw new JEDDefinitionException(id+": unknown mob spawning policy "+definition.mobs().policy());
                var monitorBinding=justepicdimensions.monitor.JEDMonitoringPolicy.binding(definition.source().value().getAsJsonObject());if(monitorBinding.policy()!=null&&!monitoring.containsKey(monitorBinding.policy()))throw new JEDDefinitionException(id+": unknown monitoring policy "+monitorBinding.policy());
                if(definition.instances()!=null&&!instances.containsKey(definition.instances()))throw new JEDDefinitionException(id+": unknown instance policy "+definition.instances());
                if(definition.pregeneration()!=null&&!pregeneration.containsKey(definition.pregeneration()))throw new JEDDefinitionException(id+": unknown pregeneration policy "+definition.pregeneration());
                if(definition.environment()!=null&&!environment.containsKey(definition.environment()))throw new JEDDefinitionException(id+": unknown environment policy "+definition.environment());
                var schedule=definition.lifecycleSchedule();if(schedule!=null){if(!schedules.containsKey(schedule))throw new JEDDefinitionException(id+": unknown lifecycle schedule "+schedule);if(id.toString().equals("minecraft:overworld")&&schedules.get(schedule).rules().stream().anyMatch(rule->rule.action()==justepicdimensions.schedule.JEDLifecycleSchedulePolicy.Action.RESET))throw new JEDDefinitionException(id+": the running Overworld cannot be reset");}
                var selectedSets=definition.generation().structureSets();if(selectedSets!=null)for(var selectedSet:selectedSets)net.minecraft.resources.RegistryFixedCodec.create(net.minecraft.core.registries.Registries.STRUCTURE_SET).parse(ops,new com.google.gson.JsonPrimitive(selectedSet.toString())).getOrThrow(error->new JEDDefinitionException(id+": invalid generation.structure_sets: "+error));
                var admission=definition.access();
                if(admission.rule()!=null&&!accessRules.containsKey(admission.rule()))throw new JEDDefinitionException(id+": unknown access rule "+admission.rule());
                if(admission.fallback()!=null&&!dimensionExists.test(admission.fallback()))throw new JEDDefinitionException(id+": unknown access fallback "+admission.fallback());
                if(admission.message()!=null)net.minecraft.network.chat.ComponentSerialization.CODEC.parse(ops,admission.message()).getOrThrow(error->new JEDDefinitionException(id+": invalid access message: "+error));
                var settings=definition.provider()==null?null:definition.provider().prepared().clientSettings();
                if(settings!=null&&settings.biomeFog())
                    for(var biome:settings.foggyBiomes())if(net.minecraft.world.level.biome.Biome.CODEC.parse(ops,new com.google.gson.JsonPrimitive(biome.toString())).error().isPresent())throw new JEDDefinitionException(id+": unknown fog biome "+biome);
                for(var rule:definition.player().loadouts().values())if(!kits.containsKey(rule.kit()))throw new JEDDefinitionException(id+": unknown loadout "+rule.kit());
                for (var reference : definition.player().groups().entrySet())
                    if (!playerGroups.getOrDefault(reference.getKey(), Map.of()).containsKey(reference.getValue()))
                        throw new JEDDefinitionException(id + ": unknown " + reference.getKey().key() + " player-state group " + reference.getValue());
                for(var rule:java.util.stream.Stream.of(definition.travel().sky(),definition.travel().below()).filter(Objects::nonNull).toList())
                    if(!dimensionExists.test(rule.dimension()))throw new JEDDefinitionException(id+": unknown travel destination "+rule.dimension());
                if (!dimensionExists.test(id) && !disabled.contains(id))
                    throw new JEDDefinitionException(id + ": JED properties target an unknown dimension");
                definitions.put(id, definition);
            } catch (JEDDefinitionException error) { errors.add(error.getMessage()); }
        }
        if(ops instanceof net.minecraft.resources.RegistryOps<com.google.gson.JsonElement> registryOps)for(var resource:resources.entrySet())if(resource.getKey().getPath().startsWith("worldgen/structure/")){
            var selected=net.minecraft.world.level.levelgen.structure.Structure.CODEC.parse(ops,new com.google.gson.JsonPrimitive(JEDRegistryResources.entry(resource.getKey(),"worldgen/structure").toString())).result();
            if(selected.isPresent()&&(!(selected.get() instanceof net.minecraft.core.Holder.Reference<?> reference)||reference.isBound())&&selected.get().value() instanceof justepicdimensions.worldgen.JEDTemplateStructure authored)try{authored.validateAssets(structureAssets,registryOps);}catch(JEDDefinitionException failure){errors.add(resource.getKey()+": "+failure.getMessage());}
        }
        fail(errors);
        return Collections.unmodifiableMap(definitions);
    }

    /** Select one new dimension plus new registry dependencies; unrelated behavior edits stay unapplied. */
    public static JEDPreparedDefinitions creation(JEDPreparedDefinitions current, JEDPreparedDefinitions authored, ResourceLocation id) {
        var stem = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "dimension/" + id.getPath() + ".json");
        if (current.resources.containsKey(stem)) throw new JEDDefinitionException("Dimension is already declared: " + id);
        if (!authored.resources.containsKey(stem)) throw new JEDDefinitionException("No dimension definition found for " + id);
        // Saved edits to existing native definitions (for example from builder save) cannot apply during play. As with reload,
        // creation keeps their live copies below and reports them as pending restart instead of refusing the new dimension.
        var pending = new TreeSet<>(current.pendingRestart);
        for (var old : current.nativeContents.entrySet()) if (!Objects.equals(old.getValue(), authored.nativeContents.get(old.getKey())))
            pending.add(old.getKey().toString());
        // World-control switches need a reopen. The new dimension uses the live values, and an edited switch is
        // reported as pending restart, as reload does.
        if (authored.worldOptions.independent() != current.worldOptions.independent()) pending.add("enableSeparateWorldInfo");
        if (authored.worldOptions.borders() != current.worldOptions.borders()) pending.add("enableSeparateWorldBorders");
        var result = new TreeMap<>(current.resources);
        for (var resource : authored.resources.entrySet()) {
            if(justepicdimensions.profile.JEDSettingProfiles.isProfile(resource.getKey())&&!result.containsKey(resource.getKey()))result.put(resource.getKey(),resource.getValue());
            String directory = JEDRegistryResources.directory(resource.getKey());
            if (directory != null && !directory.equals("dimension") && !result.containsKey(resource.getKey())) result.put(resource.getKey(), resource.getValue());
        }
        result.put(stem, authored.resources.get(stem));
        var properties = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), JEDDimensionDefinition.DIRECTORY + "/" + id.getPath() + ".json");
        if (authored.resources.containsKey(properties)) result.put(properties, authored.resources.get(properties));
        if (authored.resources.containsKey(properties)) {
            var definition=JEDDimensionDefinition.metadata(id,authored.resources.get(properties));
            if(definition.mobs()!=null&&definition.mobs().policy()!=null){var policy=definition.mobs().policy();var resource=ResourceLocation.fromNamespaceAndPath(policy.getNamespace(),justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY+"/"+policy.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            var schedule=definition.lifecycleSchedule();if(schedule!=null){var resource=ResourceLocation.fromNamespaceAndPath(schedule.getNamespace(),justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY+"/"+schedule.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            var monitoring=justepicdimensions.monitor.JEDMonitoringPolicy.binding(definition.source().value().getAsJsonObject()).policy();if(monitoring!=null){var resource=ResourceLocation.fromNamespaceAndPath(monitoring.getNamespace(),justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY+"/"+monitoring.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            var instance=definition.instances();if(instance!=null){var resource=ResourceLocation.fromNamespaceAndPath(instance.getNamespace(),justepicdimensions.instance.JEDInstancePolicy.DIRECTORY+"/"+instance.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));var policy=authored.instancePolicies().get(instance);if(policy!=null)for(var permission:policy.permissions().values())if(permission.rule()!=null){var rule=permission.rule();var key=ResourceLocation.fromNamespaceAndPath(rule.getNamespace(),justepicdimensions.access.JEDAccessRule.DIRECTORY+"/"+rule.getPath()+".json");if(!result.containsKey(key)&&authored.resources.containsKey(key))result.put(key,authored.resources.get(key));}}
            var pregen=definition.pregeneration();if(pregen!=null){var resource=ResourceLocation.fromNamespaceAndPath(pregen.getNamespace(),justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY+"/"+pregen.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            var env=definition.environment();if(env!=null){var resource=ResourceLocation.fromNamespaceAndPath(env.getNamespace(),justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY+"/"+env.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            var access=definition.access().rule();if(access!=null){var resource=ResourceLocation.fromNamespaceAndPath(access.getNamespace(),justepicdimensions.access.JEDAccessRule.DIRECTORY+"/"+access.getPath()+".json");if(!result.containsKey(resource)&&authored.resources.containsKey(resource))result.put(resource,authored.resources.get(resource));}
            for(var rule:definition.player().loadouts().values()) {
                var kit=ResourceLocation.fromNamespaceAndPath(rule.kit().getNamespace(),justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY+"/"+rule.kit().getPath()+".json");
                if(!result.containsKey(kit)&&authored.resources.containsKey(kit))result.put(kit,authored.resources.get(kit));
            }
            for (var reference : definition.player().groups().entrySet()) {
                var resource = reference.getKey().resource(reference.getValue());
                if (!result.containsKey(resource) && authored.resources.containsKey(resource))
                    result.put(resource, authored.resources.get(resource));
            }
        }
        var worldgenAssets=new TreeSet<ResourceLocation>();for(var entry:result.entrySet())if(entry.getKey().getPath().startsWith("worldgen/structure/")&&entry.getValue().value().isJsonObject()){var structure=entry.getValue().value().getAsJsonObject();if(structure.has("type")&&structure.get("type").isJsonPrimitive()&&structure.get("type").getAsString().equals("justepicdimensions:authored")&&structure.has("placement")&&structure.get("placement").isJsonObject()){var asset=structure.getAsJsonObject("placement").get("asset");if(asset!=null&&asset.isJsonPrimitive()&&asset.getAsJsonPrimitive().isString()){var key=ResourceLocation.tryParse(asset.getAsString());if(key!=null&&(key.getPath().endsWith(".nbt")||key.getPath().endsWith(".schem")))worldgenAssets.add(ResourceLocation.fromNamespaceAndPath(key.getNamespace(),(key.getPath().endsWith(".nbt")?"structure/":"justepicdimensions/schematics/")+key.getPath()));}}}
        var created=new JEDPreparedDefinitions(authored.selection, current.catalogue, result, authored.conflicts, justepicdimensions.structure.JEDStructureAssets.creation(current.structureAssets,authored.structureAssets,authored.resources.containsKey(properties)?JEDDimensionDefinition.metadata(id,authored.resources.get(properties)).spawnStructure():null,worldgenAssets),current.origins,authored.controlNotices);
        return new JEDPreparedDefinitions(created,current,id,pending);
    }
    /** A create transaction may add its target, never apply unrelated main-config edits or lose pending restart intent. */
    private JEDPreparedDefinitions(JEDPreparedDefinitions created,JEDPreparedDefinitions active,ResourceLocation target,Set<String> pending) {
        selection=created.selection;catalogue=created.catalogue;resources=created.resources;nativeContents=created.nativeContents;
        mainConfigSource=active.mainConfigSource;mainConfigValues=active.mainConfigValues;
        conflicts=created.conflicts;structureAssets=created.structureAssets;origins=active.origins;
        controls=active.controls;constructionControls=active.constructionControls;pendingRestart=Set.copyOf(pending);
        worldOptions=active.worldOptions;initialSpawnDimension=active.initialSpawnDimension;playerOptions=active.playerOptions;travelOptions=active.travelOptions;idleUnloadOptions=active.idleUnloadOptions;
        var configured=new TreeSet<>(active.configuredDisabled);var notices=new ArrayList<>(active.controlNotices);
        var properties=resources.get(ResourceLocation.fromNamespaceAndPath(target.getNamespace(),JEDDimensionDefinition.DIRECTORY+"/"+target.getPath()+".json"));
        if(properties!=null&&!JEDDimensionDefinition.metadata(target,properties).configuredEnabled()) {
            if(controls.disabling())configured.add(target);
            else notices.add(target+": lifecycle.enabled=false ignored (enableUnregisteringDimensions=false)");
        }
        configuredDisabled=Set.copyOf(configured);controlNotices=List.copyOf(notices);
        var disabled=new TreeSet<>(justepicdimensions.dimension.JEDLifecycleStore.read(selection.world()).disabled());disabled.addAll(configuredDisabled);this.disabled=Set.copyOf(disabled);
    }
    private JEDPreparedDefinitions(JEDPreparedDefinitions original, JEDWorldCatalogue catalogue, JEDConfigRepository.Selection selection) {
        this.structureAssets=original.structureAssets; this.selection = selection; this.catalogue = catalogue; this.resources = original.resources;
        var mainSource=original.mainConfigSource;
        if(selection.copies().isEmpty()&&original.selection.copies().stream().anyMatch(copy->!copy.directory())){
            var selectedMain=JEDConfigRepository.mainFile(selection).toAbsolutePath().normalize();
            if(original.selection.copies().stream().anyMatch(copy->!copy.directory()&&copy.to().toAbsolutePath().normalize().equals(selectedMain)))mainSource=selectedMain;
        }
        this.mainConfigSource=mainSource;
        this.mainConfigValues=original.mainConfigValues;
        this.nativeContents = original.nativeContents; this.conflicts = original.conflicts;
        this.disabled = original.disabled;
        this.controls=original.controls;this.constructionControls=original.constructionControls; this.origins=original.origins; this.configuredDisabled=original.configuredDisabled;
        this.pendingRestart=original.pendingRestart; this.controlNotices=original.controlNotices;
        this.worldOptions=original.worldOptions;
        this.initialSpawnDimension=original.initialSpawnDimension;
        this.playerOptions=original.playerOptions;
        this.travelOptions=original.travelOptions;
        this.idleUnloadOptions=original.idleUnloadOptions;
    }
    public JEDPreparedDefinitions withCatalogue(JEDWorldCatalogue next) { return new JEDPreparedDefinitions(this, next, selection); }
    public JEDPreparedDefinitions copiesCommitted() {
        return new JEDPreparedDefinitions(this, catalogue, new JEDConfigRepository.Selection(selection.source(), selection.world(), List.of(), selection.activeSource()));
    }
    private JEDPreparedDefinitions(JEDPreparedDefinitions authored, JEDPreparedDefinitions active, Set<String> pending) {
        selection=authored.selection;catalogue=authored.catalogue;structureAssets=authored.structureAssets;
        mainConfigSource=authored.mainConfigSource;mainConfigValues=authored.mainConfigValues.deepCopy();
        for(var field:justepicdimensions.config.JEDConfigFields.MAIN)
            if(authored.selection==active.selection&&field.timing()==justepicdimensions.config.JEDConfigFields.Timing.SELECTION)
                mainConfigValues.add(field.key(),active.mainConfigValues.get(field.key()).deepCopy());
        conflicts=authored.conflicts;controls=authored.controls;constructionControls=active.constructionControls;origins=active.origins;controlNotices=authored.controlNotices;
        configuredDisabled=active.configuredDisabled;
        var disabled=new TreeSet<>(justepicdimensions.dimension.JEDLifecycleStore.read(selection.world()).disabled());disabled.addAll(configuredDisabled);this.disabled=Set.copyOf(disabled);
        var result=new TreeMap<>(authored.resources);result.keySet().removeIf(key->JEDRegistryResources.directory(key)!=null);
        active.resources.forEach((key,value)->{if(JEDRegistryResources.directory(key)!=null)result.put(key,value);});
        resources=Map.copyOf(result);nativeContents=active.nativeContents;pendingRestart=Set.copyOf(pending);
        var options=authored.worldOptions;
        worldOptions=new justepicdimensions.world.JEDWorldOptions(active.worldOptions.independent(),active.worldOptions.borders(),options.redirect());
        initialSpawnDimension=authored.initialSpawnDimension;playerOptions=authored.playerOptions;travelOptions=authored.travelOptions;idleUnloadOptions=authored.idleUnloadOptions;
    }
    /** Reload cannot add native entries. Name the dimensions to create before the decoder reports their new dependencies as missing. */
    public void requireCreatedFirst(JEDPreparedDefinitions active) {
        var added = new TreeSet<>(nativeContents.keySet()); added.removeAll(active.nativeContents.keySet());
        if (added.isEmpty()) return;
        var dimensions = added.stream().filter(key -> "dimension".equals(JEDRegistryResources.directory(key))).map(key -> JEDRegistryResources.entry(key, "dimension").toString()).toList();
        var commands = String.join(", ", dimensions.stream().map(id -> "/jed create " + id).toList());
        throw new JEDDefinitionException(dimensions.isEmpty()
            ? "Reload cannot add the new worldgen files " + added + ". They are added when a dimension that uses them is created with /jed create, or at the next restart."
            : dimensions.size() == 1
            ? "Reload cannot add dimensions. Your JED files define a dimension that does not exist yet: " + dimensions.getFirst() + ". Create it first with " + commands + ". Its new dimension types, biomes and other worldgen files are added with it."
            : "Reload cannot add dimensions. Your JED files define " + dimensions.size() + " dimensions that do not exist yet: " + String.join(", ", dimensions) + ". Create each one first: " + commands + ". Their new dimension types, biomes and other worldgen files are added with them.");
    }
    /** Existing native IDs remain immutable during play. New IDs still require the explicit create transaction. */
    public JEDPreparedDefinitions forReload(JEDPreparedDefinitions active) {
        var pending=new TreeSet<String>();
        var changed=new TreeSet<>(nativeContents.keySet());changed.addAll(active.nativeContents.keySet());
        changed.removeIf(key->Objects.equals(nativeContents.get(key),active.nativeContents.get(key)));
        for(var key:changed) {
            if(!active.nativeContents.containsKey(key)||!nativeContents.containsKey(key))
                throw new JEDDefinitionException("Native registry additions/removals require an explicit dimension lifecycle transaction or world/server restart: "+key+". Removing a declaration never deletes save data.");
            pending.add(key.toString());
        }
        if(!configuredDisabled.equals(active.configuredDisabled))pending.add("dimension enabled/disabled configuration");
        if(controls.replacement()!=active.constructionControls.replacement())pending.add("enableReplacingRegisteredDimensions");
        if(controls.biomes()!=active.constructionControls.biomes())pending.add("enableOverrideBiomeProvider");
        if(!Objects.equals(controls.worldPreset(),active.constructionControls.worldPreset()))pending.add("world_preset (new worlds only)");
        if(worldOptions.independent()!=active.worldOptions.independent())pending.add("enableSeparateWorldInfo");
        if(worldOptions.borders()!=active.worldOptions.borders())pending.add("enableSeparateWorldBorders");
        return new JEDPreparedDefinitions(this,active,pending);
    }
    /** Re-read only main TOML. JSON, datapacks, assets and world registry data stay at the committed revision. */
    public JEDPreparedDefinitions mainConfigReload() {
        var authored=new JEDPreparedDefinitions(selection,catalogue,resources,conflicts,structureAssets,origins,List.of());
        var next=authored.forReload(this);var pending=new TreeSet<>(next.pendingRestart);pendingRestart.stream().filter(item->item.contains(":")).forEach(pending::add);
        return new JEDPreparedDefinitions(authored,this,pending);
    }
    /** Includes resolved values, provenance, selection/copy intent and conflict diagnostics; computed only on the worker. */
    public String fingerprint() {
        var value = new StringBuilder(selection.toString());
        new TreeMap<>(resources).forEach((key, document) -> value.append(key).append(JEDJson.canonical(document.value())).append(new TreeMap<>(document.origins())));
        value.append(new TreeSet<>(catalogue.creations().keySet())).append(conflicts).append(worldOptions).append(initialSpawnDimension).append(playerOptions).append(travelOptions).append(idleUnloadOptions).append(controls).append(configuredDisabled).append(pendingRestart).append(origins.existing()).append(structureAssets.fingerprint()); return justepicdimensions.dimension.JEDDimensionSync.digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void requireSameNative(JEDPreparedDefinitions current) {
        if (nativeContents.equals(current.nativeContents)) return;
        var changed = new TreeSet<ResourceLocation>(); changed.addAll(nativeContents.keySet()); changed.addAll(current.nativeContents.keySet());
        changed.removeIf(key -> Objects.equals(nativeContents.get(key), current.nativeContents.get(key)));
        throw new JEDDefinitionException("Native dimension/worldgen changes require an explicit dimension lifecycle transaction; /reload cannot apply " + changed
            + ". Active JED definitions and lighting policies were retained. Removing a declaration never deletes save data.");
    }

    public static Map<ResourceLocation, JEDClientLightPolicy> policies(Map<ResourceLocation, JEDDimensionDefinition> definitions) {
        var result = new TreeMap<ResourceLocation, JEDClientLightPolicy>();
        definitions.forEach((id, definition) -> { if (definition.clientLightPolicy() != null) result.put(id, definition.clientLightPolicy()); });
        return Collections.unmodifiableMap(result);
    }
    private static void fail(List<String> errors) {
        if (!errors.isEmpty()) throw new JEDDefinitionException("JED definitions were not applied:\n - " + String.join("\n - ", errors));
    }
    public JEDConfigRepository.Selection selection() { return selection; }
    public JEDConfigControls controls(){return controls;}
    public java.nio.file.Path mainConfigSource(){return mainConfigSource;}
    /** Effective controls; construction-only values stay at their native construction baseline. */
    public JsonObject activeMainConfig(){
        var values=mainConfigValues.deepCopy();
        values.addProperty("enableReplacingRegisteredDimensions",constructionControls.replacement());
        values.addProperty("enableUnregisteringDimensions",constructionControls.disabling());
        values.addProperty("enableOverrideBiomeProvider",constructionControls.biomes());
        values.addProperty("enableOverrideWorldProvider",controls.providers());
        values.addProperty("enableLoggingInfo",controls.information());
        values.addProperty("world_preset",constructionControls.worldPreset()==null?"":constructionControls.worldPreset().toString());
        values.addProperty("enableSeparateWorldInfo",worldOptions.independent());
        values.addProperty("enableSeparateWorldBorders",worldOptions.borders());
        values.addProperty("enableCommandRedirecting",worldOptions.redirect());
        values.addProperty("initial_spawn_dimension",initialSpawnDimension==null?"":initialSpawnDimension.toString());
        values.addProperty("enablePlayerInventoryGroups",playerOptions.inventoryGroups());
        values.addProperty("enableForcedGameModes",playerOptions.forcedModes());
        values.addProperty("normalGameMode",playerOptions.normalMode().getName());
        values.addProperty("tpjAdminOnly",travelOptions.tpjAdminOnly());
        values.addProperty("tpjAdminLevel",travelOptions.tpjAdminLevel());
        return values;
    }
    public JEDDimensionOrigins origins(){return origins;}
    public Set<ResourceLocation> configuredDisabled(){return configuredDisabled;}
    public Set<String> pendingRestart(){return pendingRestart;}
    public List<String> controlNotices(){return controlNotices;}
    public justepicdimensions.world.JEDWorldOptions worldOptions(){return worldOptions;}
    public ResourceLocation initialSpawnDimension(){return initialSpawnDimension;}
    public justepicdimensions.player.JEDPlayerOptions playerOptions(){return playerOptions;}
    public justepicdimensions.travel.JEDTravelOptions travelOptions(){return travelOptions;}
    public justepicdimensions.dimension.JEDIdleUnloadOptions idleUnloadOptions(){return idleUnloadOptions;}
    public Map<ResourceLocation,justepicdimensions.player.JEDLoadoutDefinition> loadouts(com.mojang.serialization.DynamicOps<JsonElement> ops){
        var result=new TreeMap<ResourceLocation,justepicdimensions.player.JEDLoadoutDefinition>();
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY);
            result.put(id,justepicdimensions.player.JEDLoadoutDefinition.decode(id,entry.getValue(),ops));
        }
        return Collections.unmodifiableMap(result);
    }
    public JEDWorldCatalogue catalogue() { return catalogue; }
    public Map<justepicdimensions.player.JEDPlayerCategory, Map<ResourceLocation, justepicdimensions.player.JEDPlayerGroupDefinition>> playerGroups(com.mojang.serialization.DynamicOps<JsonElement> ops) {
        var result = new EnumMap<justepicdimensions.player.JEDPlayerCategory, Map<ResourceLocation, justepicdimensions.player.JEDPlayerGroupDefinition>>(justepicdimensions.player.JEDPlayerCategory.class);
        for (var category : justepicdimensions.player.JEDPlayerCategory.values()) {
            var groups = new TreeMap<ResourceLocation, justepicdimensions.player.JEDPlayerGroupDefinition>();
            for (var entry : resources.entrySet()) if (entry.getKey().getPath().startsWith(category.directory() + "/")) {
                var id = JEDRegistryResources.entry(entry.getKey(), category.directory());
                groups.put(id, justepicdimensions.player.JEDPlayerGroupDefinition.decode(category, id, entry.getValue(), ops));
            }
            result.put(category, Collections.unmodifiableMap(groups));
        }
        return Collections.unmodifiableMap(result);
    }
    public Map<ResourceLocation,justepicdimensions.pregen.JEDPregenerationPolicy> pregenerationPolicies(){var result=new TreeMap<ResourceLocation,justepicdimensions.pregen.JEDPregenerationPolicy>();for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY+"/")){var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY);result.put(id,justepicdimensions.pregen.JEDPregenerationPolicy.decode(id,entry.getValue()));}return Map.copyOf(result);}
    /** Usable policies only: problems were already rejected by validation, or logged at startup. */
    public Map<ResourceLocation,justepicdimensions.monitor.JEDMonitoringPolicy> monitoringPolicies(){return justepicdimensions.monitor.JEDMonitoringPolicy.decodeAll(resources,new ArrayList<>());}
    public Map<ResourceLocation,justepicdimensions.instance.JEDInstancePolicy> instancePolicies(){return justepicdimensions.instance.JEDInstancePolicy.decodeAll(resources);}
    public int dimensionSyncTimeoutSeconds(){return mainConfigValues.get("dimensionSyncTimeoutSeconds").getAsInt();}
    public int instanceRegisteredLimit(){return mainConfigValues.get("instanceRegisteredLimit").getAsInt();}
    public int instanceLoadedLimit(){return mainConfigValues.get("instanceLoadedLimit").getAsInt();}
    public int pregenerationConcurrentJobs(){return mainConfigValues.get("pregenerationConcurrentJobs").getAsInt();}
    public Map<ResourceLocation,justepicdimensions.schedule.JEDLifecycleSchedulePolicy> lifecycleSchedules(){
        var result=new TreeMap<ResourceLocation,justepicdimensions.schedule.JEDLifecycleSchedulePolicy>();
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY);
            result.put(id,justepicdimensions.schedule.JEDLifecycleSchedulePolicy.decode(id,entry.getValue()));
        }return Map.copyOf(result);
    }
    public Map<ResourceLocation,justepicdimensions.environment.JEDEnvironmentPolicy> environmentPolicies(com.mojang.serialization.DynamicOps<JsonElement> ops){
        var result=new TreeMap<ResourceLocation,justepicdimensions.environment.JEDEnvironmentPolicy>();
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY);
            result.put(id,justepicdimensions.environment.JEDEnvironmentPolicy.decode(id,entry.getValue(),ops));
        }return Map.copyOf(result);
    }
    public Map<ResourceLocation,justepicdimensions.mob.JEDMobSpawnPolicy> mobSpawnPolicies(){
        var result=new TreeMap<ResourceLocation,justepicdimensions.mob.JEDMobSpawnPolicy>();
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY);
            result.put(id,justepicdimensions.mob.JEDMobSpawnPolicy.decode(id,entry.getValue()));
        }
        return Map.copyOf(result);
    }
    public Map<ResourceLocation,justepicdimensions.access.JEDAccessRule> accessRules(com.mojang.serialization.DynamicOps<JsonElement> ops){
        var result=new TreeMap<ResourceLocation,justepicdimensions.access.JEDAccessRule>();
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(justepicdimensions.access.JEDAccessRule.DIRECTORY+"/")){
            var id=JEDRegistryResources.entry(entry.getKey(),justepicdimensions.access.JEDAccessRule.DIRECTORY);
            result.put(id,justepicdimensions.access.JEDAccessRule.decode(id,entry.getValue(),ops));
        }
        return Collections.unmodifiableMap(result);
    }
    public Map<ResourceLocation, JEDSourceDocument> resources() { return resources; }
    public Map<ResourceLocation, String> nativeContents() { return nativeContents; }
    public List<JEDDefinitionResolver.Conflict> conflicts() { return conflicts; }
    public Set<ResourceLocation> disabled() { return disabled; }
}
