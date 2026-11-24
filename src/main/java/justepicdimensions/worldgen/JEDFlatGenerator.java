package justepicdimensions.worldgen;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Native flat layers plus configurable modern biomes, structures, features, and carvers. */
public final class JEDFlatGenerator extends ChunkGenerator {
    public static final MapCodec<JEDFlatGenerator> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
        FlatLevelGeneratorSettings.CODEC.fieldOf("settings").forGetter(g->g.settings),
        BiomeSource.CODEC.optionalFieldOf("biome_source").forGetter(g->g.selectedBiomes),
        NoiseGeneratorSettings.CODEC.optionalFieldOf("noise_settings").forGetter(g->g.noiseSettings),
        Codec.BOOL.optionalFieldOf("carvers",false).forGetter(g->g.carvers)
    ).apply(instance,JEDFlatGenerator::new));
    private final FlatLevelGeneratorSettings settings;
    private final Optional<BiomeSource> selectedBiomes;
    private final Optional<Holder<NoiseGeneratorSettings>> noiseSettings;
    private final boolean carvers;
    private final FlatLevelSource layers;
    private final Map<ServerLevel,NoiseBasedChunkGenerator> carvingGenerators=Collections.synchronizedMap(new WeakHashMap<>());
    public JEDFlatGenerator(FlatLevelGeneratorSettings settings,Optional<BiomeSource> biomes,Optional<Holder<NoiseGeneratorSettings>> noiseSettings,boolean carvers){
        super(biomes.orElseGet(()->new FixedBiomeSource(settings.getBiome())),biomeSettings(settings));
        this.settings=settings;this.selectedBiomes=biomes;this.noiseSettings=noiseSettings;this.carvers=carvers;this.layers=new FlatLevelSource(settings);
    }
    /** Native adjustment consumes non-solid layers; each biome must receive a detached settings copy. */
    private static java.util.function.Function<Holder<Biome>,BiomeGenerationSettings> biomeSettings(FlatLevelGeneratorSettings settings){var cache=new java.util.concurrent.ConcurrentHashMap<Holder<Biome>,BiomeGenerationSettings>();return biome->cache.computeIfAbsent(biome,selected->settings.withBiomeAndLayers(settings.getLayersInfo(),settings.structureOverrides(),selected).adjustGenerationSettings(selected));}
    @Override protected MapCodec<? extends ChunkGenerator> codec(){return CODEC;}
    public RandomState randomState(ServerLevel level){return RandomState.create(noise(level).value(),level.registryAccess().lookupOrThrow(Registries.NOISE),level.getSeed());}
    private Holder<NoiseGeneratorSettings> noise(ServerLevel level){return noiseSettings.orElseGet(()->level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));}
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets,RandomState random,long seed){Stream<Holder<StructureSet>> selected=settings.structureOverrides().map(HolderSet::stream).orElseGet(()->sets.listElements().map(h->(Holder<StructureSet>)h));return ChunkGeneratorStructureState.createForFlat(random,seed,this.biomeSource,selected);}
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender,RandomState random,StructureManager structures,ChunkAccess chunk){return layers.fillFromNoise(blender,random,structures,chunk);}
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor height,RandomState random){return layers.getBaseHeight(x,z,type,height,random);}
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor height,RandomState random){return layers.getBaseColumn(x,z,height,random);}
    @Override public int getSpawnHeight(LevelHeightAccessor height){return layers.getSpawnHeight(height);}
    @Override public void buildSurface(WorldGenRegion region,StructureManager structures,RandomState random,ChunkAccess chunk){}
    @Override public void applyCarvers(WorldGenRegion region,long seed,RandomState random,BiomeManager biomes,StructureManager structures,ChunkAccess chunk,GenerationStep.Carving step){if(carvers){var generator=carvingGenerators.computeIfAbsent(((WorldGenLevel)region).getLevel(),level->new NoiseBasedChunkGenerator(this.biomeSource,noise(level)));generator.applyCarvers(region,seed,random,biomes,structures,chunk,step);}}
    @Override public void spawnOriginalMobs(WorldGenRegion region){}
    @Override public void addDebugScreenInfo(List<String> lines,RandomState random,BlockPos pos){}
    @Override public int getMinY(){return layers.getMinY();}
    @Override public int getGenDepth(){return layers.getGenDepth();}
    @Override public int getSeaLevel(){return layers.getSeaLevel();}
}
