package justepicdimensions.builder;

import com.google.gson.*;
import com.mojang.serialization.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.worldgen.*;
import justepicdimensions.dimension.JEDRegistryTransaction;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;

/** Read-only builder operations. Sampling never requests a chunk or mutates live registries. */
@net.neoforged.fml.common.EventBusSubscriber(modid="justepicdimensions")
public final class JEDWorldgenEditor {
    private record PreviewJob(MinecraftServer server,JsonObject request,JEDRegistryTransaction registry,long revision,java.util.concurrent.CompletableFuture<JsonObject> result){}
    private static final java.util.List<PreviewJob> JOBS=new java.util.ArrayList<>();
    private static JEDRegistryTransaction scratch(MinecraftServer server,JsonObject documents){
        var additions=new TreeMap<ResourceLocation,JEDSourceDocument>();
        for(var entry:documents.entrySet())if(!Set.of("dimension","properties").contains(entry.getKey())){JEDBuilderDraft.checkDocument(entry.getKey());var id=ResourceLocation.parse(entry.getKey());String directory=JEDRegistryResources.directory(id);if(directory!=null&&!Set.of("dimension","dimension_type","worldgen/structure","worldgen/structure_set","worldgen/template_pool","worldgen/processor_list").contains(directory))additions.put(id,new JEDSourceDocument(entry.getValue().deepCopy(),"biome preview draft"));}
        return JEDRegistryTransaction.verification(server.registryAccess(),additions);
    }
    public static java.util.concurrent.CompletableFuture<JsonObject> request(MinecraftServer server,JsonObject request){
        if(!server.isSameThread())throw new IllegalStateException("Worldgen editor requires server thread");
        if(!JEDJson.string(request.get("worldgen_action"),"worldgen_action").equals("preview"))return java.util.concurrent.CompletableFuture.completedFuture(handle(server,request));
        var future=new java.util.concurrent.CompletableFuture<JsonObject>();JOBS.add(new PreviewJob(server,request.deepCopy(),scratch(server,JEDJson.object(request.get("documents"),"documents")),JEDDefinitionManager.state(server).revision(),future));return future;
    }
    @net.neoforged.bus.api.SubscribeEvent public static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        long deadline=System.nanoTime()+2_000_000;for(var job:List.copyOf(JOBS))if(job.server==event.getServer())try{if(JEDDefinitionManager.state(job.server).revision()!=job.revision)throw new JEDDefinitionException("Resources changed during biome preview; refresh to use the current resources");while(!job.registry.prepared()&&System.nanoTime()<deadline)job.registry.advance();if(job.registry.prepared()){var reply=new JsonObject();reply.addProperty("kind","worldgen");reply.add("preview",preview(job.server,job.request,job.registry));JOBS.remove(job);job.result.complete(reply);}if(System.nanoTime()>=deadline)break;}catch(Throwable failure){JOBS.remove(job);job.result.completeExceptionally(failure);}
    }
    @net.neoforged.bus.api.SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){for(var job:List.copyOf(JOBS))if(job.server==event.getServer()){JOBS.remove(job);job.result.cancel(false);}}

    @SuppressWarnings({"rawtypes","unchecked"})
    public static JsonObject resource(MinecraftServer server,String name,JsonObject documents){
        JEDBuilderDraft.checkDocument(name);var key=ResourceLocation.parse(name);String directory=JEDRegistryResources.directory(key);if(directory==null)throw new JEDDefinitionException("Expected a native worldgen resource");
        if(documents.has(name))return JEDJson.object(documents.get(name),name).deepCopy();
        var authored=JEDDefinitionManager.state(server).prepared().resources().get(key);if(authored!=null)return JEDJson.object(authored.value(),name).deepCopy();
        var type=JEDRegistryResources.types().get(directory);var registry=server.registryAccess().registryOrThrow((ResourceKey)type.key());var value=registry.get(JEDRegistryResources.entry(key,directory));if(value==null)throw new JEDDefinitionException("Unknown resource "+name);
        Codec<Object> codec=(Codec<Object>)type.elementCodec();return codec.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),value).getOrThrow(e->new JEDDefinitionException(e)).getAsJsonObject();
    }
    public static JsonObject preview(MinecraftServer server,JsonObject request){var registry=scratch(server,JEDJson.object(request.get("documents"),"documents"));while(!registry.prepared())registry.advance();return preview(server,request,registry);}
    private static JsonObject preview(MinecraftServer server,JsonObject request,JEDRegistryTransaction registry){
        var docs=JEDJson.object(request.get("documents"),"documents");var dimension=JEDJson.object(docs.get("dimension"),"dimension");var generator=dimension.getAsJsonObject("generator");if(generator==null)throw new JEDDefinitionException("Choose terrain before previewing biomes");
        var source=generator.get("biome_source");if(source==null&&generator.has("settings")&&generator.get("settings").isJsonObject()){var flat=generator.getAsJsonObject("settings");if(flat.has("biome")){var fixed=new JsonObject();fixed.addProperty("type","minecraft:fixed");fixed.add("biome",flat.get("biome"));source=fixed;}}
        if(source==null)throw new JEDDefinitionException("This generator does not expose a biome source; its Advanced JSON remains available");
        var ops=registry.ops();var biomes=BiomeSource.CODEC.parse(ops,source).getOrThrow(e->new JEDDefinitionException(e));
        long seed=WorldOptions.parseSeed(JEDJson.string(request.get("seed"),"seed")).orElseThrow(()->new JEDDefinitionException("Enter a seed for the preview"));
        int y=JEDJson.integer(request.get("y"),"y"),cx=JEDJson.integer(request.get("x"),"x"),cz=JEDJson.integer(request.get("z"),"z"),span=JEDJson.integer(request.get("span"),"span");if(span<4||span>59999968||Math.abs((long)cx)>29999984||Math.abs((long)cz)>29999984||y < -2032||y > 2031)throw new JEDDefinitionException("Preview coordinates or span outside world bounds");
        NoiseGeneratorSettings noise=registry.holder(Registries.NOISE_SETTINGS,NoiseGeneratorSettings.OVERWORLD.location()).orElseThrow().value();
        if(generator.has("settings")&&!Set.of("minecraft:flat","justepicdimensions:flat").contains(generator.get("type").getAsString())){var settings=generator.get("settings");if(settings.isJsonPrimitive()){String id=settings.getAsString();var key=ResourceLocation.parse(id);String name=key.getNamespace()+":worldgen/noise_settings/"+key.getPath()+".json";if(docs.has(name))settings=docs.get(name);}noise=NoiseGeneratorSettings.CODEC.parse(ops,settings).getOrThrow(e->new JEDDefinitionException(e)).value();}
        else if(generator.has("noise_settings"))noise=NoiseGeneratorSettings.CODEC.parse(ops,generator.get("noise_settings")).getOrThrow(e->new JEDDefinitionException(e)).value();
        var random=RandomState.create(noise,registry.lookup(Registries.NOISE).orElseThrow().getter(),seed);JEDWeightedBiomeSource.bind(random.sampler(),seed);
        var palette=new JsonArray();var indices=new JsonArray();var ids=new LinkedHashMap<String,Integer>();final int resolution=64;
        for(int z=0;z<resolution;z++)for(int x=0;x<resolution;x++){int bx=(int)Math.max(-29999984,Math.min(29999984,(long)cx-span/2L+(long)x*span/(resolution-1))),bz=(int)Math.max(-29999984,Math.min(29999984,(long)cz-span/2L+(long)z*span/(resolution-1)));var biome=biomes.getNoiseBiome(Math.floorDiv(bx,4),Math.floorDiv(y,4),Math.floorDiv(bz,4),random.sampler());String id=biome.unwrapKey().map(k->k.location().toString()).orElse("inline biome");Integer index=ids.get(id);if(index==null){index=ids.size();ids.put(id,index);palette.add(id);}indices.add(index);}
        var reply=new JsonObject();reply.addProperty("resolution",resolution);reply.add("palette",palette);reply.add("pixels",indices);return reply;
    }
    public static JsonObject handle(MinecraftServer server,JsonObject request){if(!server.isSameThread())throw new IllegalStateException("Worldgen editor requires server thread");String action=JEDJson.string(request.get("worldgen_action"),"worldgen_action");var reply=new JsonObject();reply.addProperty("kind","worldgen");if(action.equals("resource"))reply.add("document",resource(server,JEDJson.string(request.get("resource"),"resource"),JEDJson.object(request.get("documents"),"documents")));else if(action.equals("climate")){var id=ResourceLocation.parse(JEDJson.string(request.get("resource"),"preset"));var parameters=server.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,id)).parameters();var source=MultiNoiseBiomeSource.DIRECT_CODEC.codec().encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),parameters).getOrThrow(e->new JEDDefinitionException(e)).getAsJsonObject();source.addProperty("type","minecraft:multi_noise");reply.add("document",source);}else if(action.equals("preview_defaults")){var configured=JEDBuilderDraft.find(request.getAsJsonObject("documents").get("properties"),"generation.seed");var id=ResourceLocation.tryParse(request.has("target")?request.get("target").getAsString():"");var level=id==null?null:server.levels.get(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION,id));reply.addProperty("seed",configured!=null&&!configured.getAsString().equals("random")?configured.getAsString():Long.toString(level==null?server.getWorldData().worldGenOptions().seed():level.getSeed()));}else if(action.equals("structure_defaults")){var dimension=request.getAsJsonObject("documents").getAsJsonObject("dimension");var generator=dimension.getAsJsonObject("generator");JsonElement overrides=generator.has("settings")&&generator.get("settings").isJsonObject()?generator.getAsJsonObject("settings").get("structure_overrides"):null;var ids=new JsonArray();if(overrides==null)server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).entrySet().stream().filter(e->!JEDGeneratorFactory.scopedSet(e.getKey().location(),e.getValue())).map(e->e.getKey().location()).sorted().forEach(id->ids.add(id.toString()));else{var holders=net.minecraft.core.RegistryCodecs.homogeneousList(Registries.STRUCTURE_SET).parse(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),overrides).getOrThrow(e->new JEDDefinitionException(e));holders.stream().forEach(h->ids.add(h.unwrapKey().orElseThrow().location().toString()));}reply.add("sets",ids);}else if(action.equals("preview"))reply.add("preview",preview(server,request));else throw new JEDDefinitionException("Unknown worldgen editor action");return reply;}
    private JEDWorldgenEditor(){}
}
