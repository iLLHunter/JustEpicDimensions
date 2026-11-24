package justepicdimensions.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Vanilla and addon generator codecs retain their exact classes; only JED's own type is added. */
public final class JEDGeneratorFactory {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> TYPES=DeferredRegister.create(Registries.CHUNK_GENERATOR,"justepicdimensions");
    private static final DeferredRegister<MapCodec<? extends net.minecraft.world.level.biome.BiomeSource>> BIOMES=DeferredRegister.create(Registries.BIOME_SOURCE,"justepicdimensions");
    private static final DeferredRegister<net.minecraft.world.level.levelgen.structure.StructureType<?>> STRUCTURES=DeferredRegister.create(Registries.STRUCTURE_TYPE,"justepicdimensions");
    private static final DeferredRegister<net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType> PIECES=DeferredRegister.create(Registries.STRUCTURE_PIECE,"justepicdimensions");
    public static final java.util.function.Supplier<net.minecraft.world.level.levelgen.structure.StructureType<JEDTemplateStructure>> TEMPLATE_TYPE=STRUCTURES.register("authored",()->()->JEDTemplateStructure.CODEC);
    public static final java.util.function.Supplier<net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType> TEMPLATE_PIECE=PIECES.register("authored",()->JEDTemplateStructure.Piece::new);
    public static void register(IEventBus bus){TYPES.register("flat",()->JEDFlatGenerator.CODEC);TYPES.register(bus);BIOMES.register("weighted_regions",()->JEDWeightedBiomeSource.CODEC);BIOMES.register(bus);STRUCTURES.register(bus);PIECES.register(bus);}
    public static RandomState randomState(RandomState nativeState,ChunkGenerator generator,ServerLevel level){var state=generator instanceof JEDFlatGenerator flat?flat.randomState(level):nativeState;JEDWeightedBiomeSource.bind(state.sampler(),level.getSeed());JEDTemplateStructure.bind(level,state);return state;}
    /** Private copies enter a world only through an explicit selection; addon generator classes remain intact. */
    public static boolean scopedSet(net.minecraft.resources.ResourceLocation id,net.minecraft.world.level.levelgen.structure.StructureSet set){return id.getNamespace().equals("justepicdimensions")&&id.getPath().startsWith("builder/structure_sets/")||set.structures().stream().anyMatch(e->e.structure().value() instanceof JEDTemplateStructure);}
    /** Filter the argument without replacing ChunkMap's native invocation, which other mods wrap. */
    public static net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> structureSets(net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> sets,ServerLevel level){
        var context=JEDWorldgenContext.context(level);var ids=context==null?null:context.requested().structureSets();
        java.util.List<net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> selected;
        if(ids!=null)selected=ids.stream().map(id->sets.get(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE_SET,id)).orElseThrow(()->new justepicdimensions.definition.JEDDefinitionException("Unknown selected structure set "+id))).toList();
        else{var available=sets.listElements().toList();selected=available.stream().filter(holder->!scopedSet(holder.key().location(),holder.value())).toList();if(selected.size()==available.size())return sets;}
        if(sets instanceof net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.StructureSet> registry)return new net.minecraft.core.HolderLookup.RegistryLookup.Delegate<net.minecraft.world.level.levelgen.structure.StructureSet>(){public net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.levelgen.structure.StructureSet> parent(){return registry;}@Override public java.util.stream.Stream<net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> listElements(){return selected.stream();}};
        return new net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet>(){
            public java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> get(net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.StructureSet> key){return sets.get(key);}
            public java.util.Optional<net.minecraft.core.HolderSet.Named<net.minecraft.world.level.levelgen.structure.StructureSet>> get(net.minecraft.tags.TagKey<net.minecraft.world.level.levelgen.structure.StructureSet> tag){return sets.get(tag);}
            public java.util.stream.Stream<net.minecraft.core.Holder.Reference<net.minecraft.world.level.levelgen.structure.StructureSet>> listElements(){return selected.stream();}
            public java.util.stream.Stream<net.minecraft.core.HolderSet.Named<net.minecraft.world.level.levelgen.structure.StructureSet>> listTags(){return sets.listTags();}
        };
    }
    /** Explicit flat selections override its settings' structure list; otherwise retain native/modded results. */
    public static net.minecraft.world.level.chunk.ChunkGeneratorStructureState structureState(net.minecraft.world.level.chunk.ChunkGeneratorStructureState nativeState,ChunkGenerator generator,net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> sets,RandomState random,long seed,ServerLevel level){
        var context=JEDWorldgenContext.context(level);var ids=context==null?null:context.requested().structureSets();
        if(ids!=null&&(generator.getClass()==FlatLevelSource.class||generator instanceof JEDFlatGenerator))return net.minecraft.world.level.chunk.ChunkGeneratorStructureState.createForFlat(random,seed,generator.getBiomeSource(),sets.listElements().map(h->(net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.StructureSet>)h));
        return nativeState;
    }
    public static net.minecraft.world.level.chunk.ChunkGeneratorStructureState createState(ChunkGenerator generator,net.minecraft.core.HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> sets,RandomState random,long seed,ServerLevel level){
        var selected=structureSets(sets,level);return structureState(generator.createState(selected,random,seed),generator,selected,random,seed,level);
    }
    public static boolean isFlat(ChunkGenerator generator){return generator instanceof FlatLevelSource||generator instanceof JEDFlatGenerator;}
    private JEDGeneratorFactory(){}
}
