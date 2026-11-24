package justepicdimensions.definition;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Ownership comes from native source packs, never a namespace convention or whether a level is loaded. */
public record JEDDimensionOrigins(Set<ResourceLocation> existing, Map<ResourceLocation,JEDSourceDocument> originals) {
    public JEDDimensionOrigins { existing = Set.copyOf(existing); originals = Map.copyOf(originals); }
    public static JEDDimensionOrigins read(ResourceManager manager) {
        var existing = new TreeSet<ResourceLocation>();
        existing.add(ResourceLocation.withDefaultNamespace("overworld"));
        existing.add(ResourceLocation.withDefaultNamespace("the_nether"));
        existing.add(ResourceLocation.withDefaultNamespace("the_end"));
        var originals = new TreeMap<ResourceLocation,JEDSourceDocument>();
        for (var entry : manager.listResourceStacks("dimension", id -> id.getPath().endsWith(".json")).entrySet()) {
            for (var resource : entry.getValue()) {
                // WORLD packs are authored save datapacks. Built-in mod/vanilla packs own existing dimensions.
                if (resource.source().location().source() == PackSource.WORLD) continue;
                try (var reader = resource.openAsReader()) {
                    var id = JEDRegistryResources.entry(entry.getKey(), "dimension"); existing.add(id);
                    originals.put(entry.getKey(), new JEDSourceDocument(JEDJson.read(reader, entry.getKey().toString()),
                        "native " + resource.sourcePackId() + " / " + entry.getKey()));
                } catch (IOException error) { throw new JEDDefinitionException("Cannot read original dimension " + entry.getKey(), error); }
            }
        }
        return new JEDDimensionOrigins(existing, originals);
    }

    /** Only JED JSON is gated. Ordinary datapack priority and codec definitions remain native. */
    public JEDSourceDocument filter(ResourceLocation resource, JEDSourceDocument base, JEDSourceDocument overlay,
                                     JEDConfigControls controls, List<String> notices) {
        if (overlay == null || !resource.getPath().startsWith("dimension/") || !resource.getPath().endsWith(".json")) return overlay;
        var id = JEDRegistryResources.entry(resource, "dimension");
        if (!existing.contains(id)) return overlay;
        if (!controls.replacement()) { notices.add(id + ": JED native replacement ignored (enableReplacingRegisteredDimensions=false)"); return null; }
        if (controls.biomes() || base == null) return overlay;
        var json = overlay.value().getAsJsonObject();
        if (!json.has("generator")) return overlay;
        var generator = json.getAsJsonObject("generator");
        var baseGenerator=base.value().getAsJsonObject().getAsJsonObject("generator");
        if(generator.has("type")&&baseGenerator!=null&&baseGenerator.has("type")&&!generator.get("type").equals(baseGenerator.get("type")))return overlay;
        boolean changed = generator.has("biome_source") || generator.has("settings") && generator.get("settings").isJsonObject() && generator.getAsJsonObject("settings").has("biome");
        if (!changed) return overlay;
        var filtered = json.deepCopy(); var g = filtered.getAsJsonObject("generator"); g.remove("biome_source");
        if (g.has("settings") && g.get("settings").isJsonObject()) g.getAsJsonObject("settings").remove("biome");
        notices.add(id + ": JED biome selection ignored (enableOverrideBiomeProvider=false)");
        return new JEDSourceDocument(filtered, overlay.origins());
    }
}
