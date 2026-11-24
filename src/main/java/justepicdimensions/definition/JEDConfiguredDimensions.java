package justepicdimensions.definition;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Construction-time configuration. Never edits a published registry or an initialized generator. */
public final class JEDConfiguredDimensions {
    private record Binding(JEDPreparedDefinitions prepared, RegistryAccess.Frozen worldgen) {}
    public record Baking(WorldDimensions dimensions, Registry<LevelStem> registry) {}
    private static final Map<Registry<LevelStem>,Binding> BINDINGS=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Registry<LevelStem>,JsonObject> BASELINES=Collections.synchronizedMap(new WeakHashMap<>());

    public static void bind(WorldLoader.DataLoadContext context) {
        if(context.resources() instanceof JEDDefinitionResources resources)
            BINDINGS.put(context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM),new Binding(resources.prepared(),context.datapackWorldgen()));
    }
    public static Baking prepare(WorldDimensions original,Registry<LevelStem> registry) {
        var binding=BINDINGS.get(registry);if(binding==null)return new Baking(original,registry);
        var prepared=binding.prepared();var controls=prepared.controls();var ops=RegistryOps.create(JsonOps.INSTANCE,binding.worldgen());
        var baseline=baseline(prepared.selection().world());
        var originals=new HashMap<ResourceKey<LevelStem>,LevelStem>();
        for(var id:prepared.origins().existing()) {
            var key=ResourceKey.create(Registries.LEVEL_STEM,id);LevelStem value=null;
            if(baseline.has(id.toString()))value=LevelStem.CODEC.parse(ops,baseline.get(id.toString())).getOrThrow(error->new JEDDefinitionException("Invalid saved native dimension baseline "+id+": "+error));
            else {
                var document=prepared.origins().originals().get(justepicdimensions.dimension.JEDLifecycleStore.stem(id));
                if(document!=null)value=LevelStem.CODEC.parse(ops,document.value()).getOrThrow(error->new JEDDefinitionException("Invalid original dimension "+id+": "+error));
                else value=original.dimensions().get(key);
                if(value!=null)baseline.add(id.toString(),LevelStem.CODEC.encodeStart(ops,value).getOrThrow());
            }
            if(value!=null)originals.put(key,value);
        }
        var dimensions=new HashMap<>(original.dimensions());
        if(controls.worldPreset()!=null&&isNew(prepared.selection().world())) {
            var preset=binding.worldgen().registryOrThrow(Registries.WORLD_PRESET).getOptional(controls.worldPreset())
                .orElseThrow(()->new JEDDefinitionException("Unknown JED world_preset "+controls.worldPreset()+"; use a registered native world preset"));
            dimensions.putAll(preset.createWorldDimensions().dimensions());
            // The selected preset, before explicit dimension JSON, is the original for this fresh world.
            for(var entry:dimensions.entrySet())if(prepared.origins().existing().contains(entry.getKey().location())) {
                originals.put(entry.getKey(),entry.getValue());baseline.add(entry.getKey().location().toString(),LevelStem.CODEC.encodeStart(ops,entry.getValue()).getOrThrow());
            }
        }
        if(!controls.replacement())dimensions.putAll(originals);
        if(!controls.biomes())for(var entry:new ArrayList<>(dimensions.entrySet())) {
            var previous=originals.get(entry.getKey());if(previous!=null)dimensions.put(entry.getKey(),retainBiomes(entry.getValue(),previous,ops,entry.getKey().location()));
        }
        dimensions.keySet().removeIf(key->prepared.disabled().contains(key.location()));
        var filtered=new MappedRegistry<LevelStem>(Registries.LEVEL_STEM,registry.registryLifecycle());
        registry.holders().forEach(holder->{
            var id=holder.key().location();if(prepared.disabled().contains(id))return;
            var value=holder.value();var document=prepared.resources().get(justepicdimensions.dimension.JEDLifecycleStore.stem(id));
            boolean json=document!=null&&document.origins().values().stream().anyMatch(source->source.startsWith("JSON "));
            if(json&&!controls.biomes()&&originals.containsKey(holder.key()))value=retainBiomes(value,originals.get(holder.key()),ops,id);
            filtered.register(holder.key(),value,registry.registrationInfo(holder.key()).orElseThrow());
        });
        var next=filtered.freeze();BASELINES.put(next,baseline);BINDINGS.put(next,binding);
        return new Baking(new WorldDimensions(dimensions),next);
    }
    private static boolean isNew(Path world){return world==null||!Files.exists(world.resolve("level.dat"))&&!Files.exists(world.resolve("level.dat_old"));}
    private static JsonObject baseline(Path world) {
        if(world==null)return new JsonObject();var file=baselineFile(world);if(!Files.exists(file))return new JsonObject();
        var json=JEDJson.object(JEDJson.read(file),file.toString());JEDJson.keys(json,Set.of("format_version","dimensions"),file.toString());
        if(JEDJson.integer(json.get("format_version"),file.toString())!=1)throw new JEDDefinitionException("Unsupported native dimension baseline version: "+file);
        return JEDJson.object(json.get("dimensions"),file.toString());
    }
    public static Path baselineFile(Path world){return world.resolve("data/justepicdimensions/native_configuration_baseline.json");}
    public static WorldDimensions.Complete baked(WorldDimensions.Complete complete,Registry<LevelStem> input) {
        var binding=BINDINGS.get(input);if(binding!=null)complete=JEDRegistryStability.dimensions(complete,binding.prepared());
        var baseline=BASELINES.get(input);if(baseline!=null)BASELINES.put(complete.dimensions(),baseline);
        return complete;
    }
    public static void saveBaseline(Registry<LevelStem> registry,Path world) {
        var baseline=BASELINES.get(registry);if(baseline==null)return;
        var root=new JsonObject();root.addProperty("format_version",1);root.add("dimensions",baseline);
        var file=baselineFile(world);justepicdimensions.dimension.JEDDimensionFiles.requireSafe(world,file,false);JEDJson.writeAtomic(file,root);
    }
    private static LevelStem retainBiomes(LevelStem selected,LevelStem original,RegistryOps<JsonElement> ops,ResourceLocation id) {
        var desired=BiomeSource.CODEC.encodeStart(ops,original.generator().getBiomeSource()).getOrThrow();
        if(desired.equals(BiomeSource.CODEC.encodeStart(ops,selected.generator().getBiomeSource()).getOrThrow()))return selected;
        var json=ChunkGenerator.CODEC.encodeStart(ops,selected.generator()).getOrThrow().getAsJsonObject();
        if(json.has("biome_source"))json.add("biome_source",desired);
        else if(json.has("settings")&&json.get("settings").isJsonObject()&&json.getAsJsonObject("settings").has("biome")
                &&original.generator().getBiomeSource().possibleBiomes().size()==1)
            json.getAsJsonObject("settings").add("biome",net.minecraft.world.level.biome.Biome.CODEC.encodeStart(ops,original.generator().getBiomeSource().possibleBiomes().iterator().next()).getOrThrow());
        else throw new JEDDefinitionException(id+": this generator cannot preserve the original biome source with enableOverrideBiomeProvider=false; retain the original generator or enable biome overrides");
        var generator=ChunkGenerator.CODEC.parse(ops,json).getOrThrow(error->new JEDDefinitionException(id+": cannot retain original biome source: "+error));
        if(!BiomeSource.CODEC.encodeStart(ops,generator.getBiomeSource()).getOrThrow().equals(desired))throw new JEDDefinitionException(id+": generator codec did not preserve the original biome source");
        return new LevelStem(selected.type(),generator);
    }
    private JEDConfiguredDimensions(){}
}
