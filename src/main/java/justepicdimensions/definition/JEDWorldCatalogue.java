package justepicdimensions.definition;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Durable validated creation intent, recorded before native registry publication; level loading is separate. */
public final class JEDWorldCatalogue {
    public record Creation(ResourceLocation dimension, Map<ResourceLocation, JEDSourceDocument> resources) {
        public Creation { resources = Map.copyOf(resources); }
    }
    private final Path file;
    private final Map<ResourceLocation, Creation> creations;

    private JEDWorldCatalogue(Path file, Map<ResourceLocation, Creation> creations) { this.file = file; this.creations = Map.copyOf(creations); }
    public static JEDWorldCatalogue read(Path world) {
        Path file = world == null ? null : world.resolve("data/justepicdimensions/dimension_catalogue.json");
        var records = new TreeMap<ResourceLocation, Creation>();
        if (file != null && Files.exists(file)) {
            var root = JEDJson.object(JEDJson.read(file), file.toString());
            JEDJson.keys(root, Set.of("format_version", "dimensions"), file.toString());
            if (JEDJson.integer(root.get("format_version"), file.toString()) != 1) throw new JEDDefinitionException(file + ": unsupported format_version");
            var dimensions = JEDJson.object(root.get("dimensions"), file.toString());
            for (var dimension : dimensions.entrySet()) {
                var id = JEDDimensionDefinition.id(dimension.getKey(), file.toString());
                var resources = new TreeMap<ResourceLocation, JEDSourceDocument>();
                for (var resource : JEDJson.object(dimension.getValue(), id.toString()).entrySet()) {
                    var key = JEDDimensionDefinition.id(resource.getKey(), file.toString());
                    if (!key.getPath().endsWith(".json") || JEDRegistryResources.directory(key) == null
                        && !key.getPath().startsWith(JEDDimensionDefinition.DIRECTORY + "/")
                        && !key.getPath().startsWith(justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY + "/")
                        && justepicdimensions.player.JEDPlayerCategory.resourceCategory(key).isEmpty()
                        && !justepicdimensions.profile.JEDSettingProfiles.isProfile(key)
                        && !key.getPath().startsWith(justepicdimensions.access.JEDAccessRule.DIRECTORY+"/")
                        && !key.getPath().startsWith(justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY+"/")
                        && !key.getPath().startsWith(justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY+"/") && !key.getPath().startsWith(justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY+"/")&&!key.getPath().startsWith(justepicdimensions.instance.JEDInstancePolicy.DIRECTORY+"/")
                        && !key.getPath().startsWith(justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY+"/")
                        && !key.getPath().startsWith(justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY+"/"))
                        throw new JEDDefinitionException(file + ": unsupported creation resource " + key);
                    resources.put(key, new JEDSourceDocument(resource.getValue(), "saved creation " + file + " / " + id));
                }
                requireStem(id, resources);
                records.put(id, new Creation(id, resources));
            }
        }
        return new JEDWorldCatalogue(file, records);
    }

    public Map<ResourceLocation, Creation> creations() { return creations; }
    public Map<ResourceLocation, JEDSourceDocument> resources() {
        var result = new TreeMap<ResourceLocation, JEDSourceDocument>();
        for (var creation : creations.values()) creation.resources().forEach((key, document) -> {
            var old = result.putIfAbsent(key, document);
            if (old != null && !old.value().equals(document.value())) throw new JEDDefinitionException("Saved creation records disagree for " + key);
        });
        return Collections.unmodifiableMap(result);
    }

    /** This does not register, load, unload or delete a dimension. The lifecycle owner supplies validated resources. */
    public JEDWorldCatalogue record(Creation creation) {
        if (file == null) throw new JEDDefinitionException("Cannot persist a runtime dimension without a selected save");
        requireStem(creation.dimension(), creation.resources());
        // Never overwrite a newer durable intent using an older in-memory catalogue instance.
        var disk = read(file.getParent().getParent().getParent());
        for (var existing : creations.entrySet()) {
            var saved = disk.creations.get(existing.getKey());
            if (saved == null || !sameResources(existing.getValue(), saved))
                throw new JEDDefinitionException("Saved creation catalogue changed or removed an existing entry: " + existing.getKey());
        }
        var next = new TreeMap<>(disk.creations);
        var previous = next.putIfAbsent(creation.dimension(), creation);
        if (previous != null) throw new JEDDefinitionException("Creation already recorded: " + creation.dimension());
        var result = new JEDWorldCatalogue(file, next);
        result.resources(); // Reject conflicting shared resources before writing.
        result.save();
        return result;
    }

    private static boolean sameResources(Creation first, Creation second) {
        if (!first.resources().keySet().equals(second.resources().keySet())) return false;
        return first.resources().entrySet().stream().allMatch(entry -> entry.getValue().value().equals(second.resources().get(entry.getKey()).value()));
    }

    private static void requireStem(ResourceLocation dimension, Map<ResourceLocation, JEDSourceDocument> resources) {
        var stem = ResourceLocation.fromNamespaceAndPath(dimension.getNamespace(), "dimension/" + dimension.getPath() + ".json");
        if (!resources.containsKey(stem)) throw new JEDDefinitionException("Creation record lacks native dimension resource " + stem);
    }
    private void save() {
        var root = new JsonObject(); root.addProperty("format_version", 1);
        var dimensions = new JsonObject();
        new TreeMap<>(creations).forEach((id, creation) -> {
            var resources = new JsonObject(); new TreeMap<>(creation.resources()).forEach((key, value) -> resources.add(key.toString(), value.value()));
            dimensions.add(id.toString(), resources);
        });
        root.add("dimensions", dimensions); JEDJson.writeAtomic(file, root);
    }
}
