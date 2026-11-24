package justepicdimensions.definition;

import java.io.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** ResourceManager chooses the winning complete datapack file; no cross-pack field inheritance. */
public final class JEDDatapackSource {
    public static Map<ResourceLocation, JEDSourceDocument> read(ResourceManager manager) {
        return read(manager, null);
    }
    public static Map<ResourceLocation, JEDSourceDocument> read(ResourceManager manager, JEDDocumentCache cache) {
        var files = new TreeMap<ResourceLocation, JEDSourceDocument>();
        var nativeDirectories = JEDRegistryResources.types().keySet();
        var directories = new ArrayList<>(nativeDirectories);
        directories.add(JEDDimensionDefinition.DIRECTORY);
        directories.add(justepicdimensions.profile.JEDSettingProfiles.DIRECTORY);
        directories.add(justepicdimensions.access.JEDAccessRule.DIRECTORY);
        directories.add(justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY);
        directories.add(justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY);
        directories.add(justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY);
        directories.add(justepicdimensions.instance.JEDInstancePolicy.DIRECTORY);
        directories.add(justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY);
        directories.add(justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY);
        directories.add(justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY);
        for (var category : justepicdimensions.player.JEDPlayerCategory.values()) directories.add(category.directory());
        for (String directory : directories) {
            boolean nativeRegistry = nativeDirectories.contains(directory);
            for (var entry : manager.listResources(directory, key -> key.getPath().endsWith(".json")).entrySet()) {
                try (var reader = entry.getValue().openAsReader()) {
                    String source = "datapack " + entry.getValue().sourcePackId() + " / data/" + entry.getKey().getNamespace() + "/" + entry.getKey().getPath();
                    JEDDocumentCache.cancelled();
                    files.put(entry.getKey(), new JEDSourceDocument(read(reader, source, nativeRegistry, cache), source));
                } catch (IOException error) { throw new JEDDefinitionException("Cannot read datapack resource " + entry.getKey(), error); }
            }
        }
        return Collections.unmodifiableMap(files);
    }

    public static List<JEDDefinitionResolver.Conflict> conflicts(ResourceManager manager, Map<ResourceLocation, JEDSourceDocument> winners) {
        return conflicts(manager, winners, null);
    }
    public static List<JEDDefinitionResolver.Conflict> conflicts(ResourceManager manager, Map<ResourceLocation, JEDSourceDocument> winners, JEDDocumentCache cache) {
        var result = new ArrayList<JEDDefinitionResolver.Conflict>();
        var nativeDirectories = JEDRegistryResources.types().keySet();
        for (var entry : winners.entrySet()) {
            var stack = manager.getResourceStack(entry.getKey());
            if (stack.size() < 2) continue;
            // Only the immediate loser matters for the applied choice; all origins remain inspectable through pack resources.
            var loser = stack.get(stack.size() - 2);
            try (var reader = loser.openAsReader()) {
                String source = entry.getKey() + " / " + loser.sourcePackId();
                boolean nativeRegistry = nativeDirectories.stream().anyMatch(directory -> entry.getKey().getPath().startsWith(directory + "/"));
                var old = read(reader, source, nativeRegistry, cache);
                if (!old.equals(entry.getValue().value())) result.add(new JEDDefinitionResolver.Conflict(entry.getKey().toString(), "$ (whole datapack file)",
                    "datapack " + loser.sourcePackId(), old.toString(), entry.getValue().origin(""), entry.getValue().value().toString()));
            } catch (IOException | JEDDefinitionException error) {
                // A shadowed file is not an input. Diagnostics must not make an invalid losing pack override a valid winner.
                result.add(new JEDDefinitionResolver.Conflict(entry.getKey().toString(), "$ (whole datapack file)",
                    "datapack " + loser.sourcePackId(), "<unreadable shadowed resource: " + error.getMessage() + ">",
                    entry.getValue().origin(""), entry.getValue().value().toString()));
            }
        }
        return List.copyOf(result);
    }
    private static com.google.gson.JsonElement read(Reader reader, String source, boolean nativeRegistry, JEDDocumentCache cache) throws IOException {
        return nativeRegistry ? (cache == null ? JEDJson.readNative(reader, source) : cache.readNative(reader, source))
            : (cache == null ? JEDJson.read(reader, source) : cache.read(reader, source));
    }
    private JEDDatapackSource() {}
}
