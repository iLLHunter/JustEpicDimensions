package justepicdimensions.definition;

import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.minecraft.tags.TagKey;

/** Native registry paths/codecs, including registry types contributed by installed addons. */
public final class JEDRegistryResources {
    public static Map<String, RegistryDataLoader.RegistryData<?>> types() {
        var result = new LinkedHashMap<String, RegistryDataLoader.RegistryData<?>>();
        for (var data : DataPackRegistriesHooks.getDataPackRegistries()) result.put(Registries.elementsDirPath(data.key()), data);
        for (var data : RegistryDataLoader.DIMENSION_REGISTRIES) result.put(Registries.elementsDirPath(data.key()), data);
        return result;
    }

    public static String directory(ResourceLocation resource) {
        for (String directory : types().keySet()) if (resource.getPath().startsWith(directory + "/") && resource.getPath().endsWith(".json")) return directory;
        return null;
    }

    public static ResourceLocation entry(ResourceLocation resource, String directory) {
        return JEDDimensionDefinition.id(resource.getNamespace() + ":" + resource.getPath().substring(directory.length() + 1, resource.getPath().length() - 5), resource.toString());
    }

    public static void validate(Map<ResourceLocation, JEDSourceDocument> resources, RegistryAccess registries, List<String> errors) {
        // RegistryDataLoader accepts forward/empty named tags through asTagAddingLookup.
        // These verification decodes are discarded: use detached named sets so a rejected
        // candidate cannot insert tag placeholders into the live registry.
        var ops = RegistryOps.create(JsonOps.INSTANCE, new RegistryOps.RegistryInfoLookup(){
            public <T> Optional<RegistryOps.RegistryInfo<T>> lookup(ResourceKey<? extends Registry<? extends T>> key){
                return registries.registry(key).map(registry->{
                    var pending=new HashMap<TagKey<T>,HolderSet.Named<T>>();
                    var getter=new HolderLookup.RegistryLookup.Delegate<T>(){
                        public HolderLookup.RegistryLookup<T> parent(){return registry.asLookup();}
                        @Override public Optional<HolderSet.Named<T>> get(TagKey<T> tag){return Optional.of(registry.getTag(tag).orElseGet(()->pending.computeIfAbsent(tag,k->new HolderSet.Named<>(registry.holderOwner(),k))));}
                    };
                    return new RegistryOps.RegistryInfo<>(registry.holderOwner(),getter,registry.registryLifecycle());
                });
            }
        });
        var conditionalOps = new net.neoforged.neoforge.common.conditions.ConditionalOps<>(ops,
            net.neoforged.neoforge.common.conditions.ICondition.IContext.TAGS_INVALID);
        var types = types();
        for (var entry : resources.entrySet()) {
            String directory = directory(entry.getKey());
            if (directory == null) continue;
            // Match RegistryDataLoader: a false native condition skips the decoder and its unavailable references.
            var decoder = net.neoforged.neoforge.common.conditions.ConditionalOps.createConditionalCodec(
                net.neoforged.neoforge.common.util.NeoForgeExtraCodecs.decodeOnly(types.get(directory).elementCodec()));
            var result = decoder.parse(conditionalOps, entry.getValue().value());
            result.error().ifPresent(error -> errors.add(entry.getKey() + " (" + entry.getValue().origin("") + "): " + error.message()));
        }
    }
    /** Minecraft's flat generator settings (also used by JED's flat generator) silently replace an unknown biome with
     * plains. Returns the problem for a dimension definition naming a biome that does not exist, otherwise null. */
    public static String unknownFlatBiome(ResourceLocation key, com.google.gson.JsonElement stem, java.util.function.Predicate<ResourceLocation> biomeExists) {
        if (!stem.isJsonObject() || !(stem.getAsJsonObject().get("generator") instanceof com.google.gson.JsonObject generator)) return null;
        if (!(generator.get("type") instanceof com.google.gson.JsonPrimitive type) || !type.isString()) return null;
        var kind = ResourceLocation.tryParse(type.getAsString());
        if (kind == null || !kind.equals(ResourceLocation.withDefaultNamespace("flat")) && !kind.equals(ResourceLocation.fromNamespaceAndPath("justepicdimensions", "flat"))) return null;
        if (!(generator.get("settings") instanceof com.google.gson.JsonObject settings) || !(settings.get("biome") instanceof com.google.gson.JsonPrimitive biome) || !biome.isString()) return null;
        var id = ResourceLocation.tryParse(biome.getAsString());
        if (id != null && biomeExists.test(id)) return null;
        return key + ": flat generator biome " + biome.getAsString() + " does not exist; Minecraft would silently use minecraft:plains instead";
    }
    /** Flat-generator biome problems among these documents; biomes may come from the registry or the documents themselves. */
    public static Map<ResourceLocation, String> unknownFlatBiomes(Map<ResourceLocation, JEDSourceDocument> resources, RegistryAccess registries) {
        var biomes = registries.registryOrThrow(Registries.BIOME); var problems = new TreeMap<ResourceLocation, String>();
        for (var entry : resources.entrySet()) if ("dimension".equals(directory(entry.getKey()))) {
            var problem = unknownFlatBiome(entry.getKey(), entry.getValue().value(), id -> biomes.containsKey(id) || resources.containsKey(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "worldgen/biome/" + id.getPath() + ".json")));
            if (problem != null) problems.put(entry.getKey(), problem);
        }
        return problems;
    }
    private JEDRegistryResources() {}
}
