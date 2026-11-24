package justepicdimensions.worldgen;

import com.google.gson.*;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.structure.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;

/** Authored assets participate in native starts, saving and chunk-clipped placement. */
public final class JEDTemplateStructure extends Structure {
    public static final Codec<JsonObject> PLACEMENT=Codec.PASSTHROUGH.xmap(d->JEDJson.object(d.convert(JsonOps.INSTANCE).getValue(),"placement"),j->new Dynamic<>(JsonOps.INSTANCE,j));
    public static final MapCodec<JEDTemplateStructure> CODEC=RecordCodecBuilder.mapCodec(i->i.group(settingsCodec(i),PLACEMENT.fieldOf("placement").forGetter(s->s.placement),Codec.STRING.optionalFieldOf("height_mode","surface").forGetter(s->s.mode),Codec.INT.optionalFieldOf("min_y").forGetter(s->Optional.ofNullable(s.minY)),Codec.INT.optionalFieldOf("max_y").forGetter(s->Optional.ofNullable(s.maxY))).apply(i,JEDTemplateStructure::new));
    private final JsonObject placement;private final JEDStructureDefinition definition;private final String mode;private final Integer minY,maxY;
    public JEDTemplateStructure(StructureSettings settings,JsonObject placement,String mode,Optional<Integer> minY,Optional<Integer> maxY){super(settings);this.placement=placement.deepCopy();this.definition=JEDStructureDefinition.decode(placement,"authored structure");this.mode=mode;this.minY=minY.orElse(null);this.maxY=maxY.orElse(null);if(!Set.of("surface","underground","cave_floor","ceiling","fixed","range").contains(mode))throw new IllegalArgumentException("Unknown structure height_mode");if(!mode.equals("surface")&&this.minY==null)throw new IllegalArgumentException("This structure height mode requires min_y");if(Set.of("underground","cave_floor","ceiling","range").contains(mode)&&(this.maxY==null||this.minY>this.maxY))throw new IllegalArgumentException("Structure min_y must not exceed max_y");}
    @Override public StructureType<?> type(){return JEDGeneratorFactory.TEMPLATE_TYPE.get();}
    public void validateAssets(JEDStructureAssets assets,net.minecraft.resources.RegistryOps<JsonElement> ops){
        var lookups=HolderLookup.Provider.create(java.util.stream.Stream.of(lookup(ops,Registries.BLOCK),lookup(ops,Registries.BIOME)));
        JEDTemplateFootprint.compute(definition,assets.get(definition.resource()),lookups).requireNativeRange(this::adjustBoundingBox,definition.asset().toString());
    }
    private static <T> HolderLookup.RegistryLookup<T> lookup(net.minecraft.resources.RegistryOps<JsonElement> ops,net.minecraft.resources.ResourceKey<? extends Registry<? extends T>> key){
        var getter=ops.getter(key).orElseThrow(()->new JEDDefinitionException("Missing registry for structure validation: "+key));
        return new HolderLookup.RegistryLookup<T>(){
            public net.minecraft.resources.ResourceKey<? extends Registry<? extends T>> key(){return key;}
            public Lifecycle registryLifecycle(){return Lifecycle.stable();}
            public Optional<Holder.Reference<T>> get(net.minecraft.resources.ResourceKey<T> entry){return getter.get(entry);}
            public Optional<HolderSet.Named<T>> get(net.minecraft.tags.TagKey<T> tag){return getter.get(tag);}
            public java.util.stream.Stream<Holder.Reference<T>> listElements(){return java.util.stream.Stream.empty();}
            public java.util.stream.Stream<HolderSet.Named<T>> listTags(){return java.util.stream.Stream.empty();}
        };
    }
    private record GeometryKey(String hash,JEDStructureDefinition definition){}
    private record Assets(JEDStructureAssets assets,HolderLookup.Provider registries,java.util.concurrent.ConcurrentMap<GeometryKey,JEDTemplateFootprint> geometry){}
    private static final Map<RandomState,Assets> STATES=Collections.synchronizedMap(new WeakHashMap<>());
    private static final JEDWeakIdentityMap<ServerLevel,Assets> LEVELS=new JEDWeakIdentityMap<>();
    private static final JEDWeakIdentityMap<StructurePieceSerializationContext,Assets> CONTEXTS=new JEDWeakIdentityMap<>();
    private static Assets snapshot(ServerLevel level){return new Assets(JEDDefinitionManager.state(level.getServer()).prepared().structureAssets(),level.registryAccess(),new java.util.concurrent.ConcurrentHashMap<>());}
    public static void bind(ServerLevel level,RandomState random){var assets=snapshot(level);LEVELS.put(level,assets);STATES.put(random,assets);}
    /** A native context is a record shared by value across dimensions; bind its identity to the actual level. */
    public static StructurePieceSerializationContext serializationContext(StructurePieceSerializationContext context,ServerLevel level){var assets=LEVELS.get(level);if(assets!=null)CONTEXTS.put(context,assets);return context;}
    private static JEDTemplateFootprint geometry(Assets resources,JEDStructureDefinition definition){var asset=resources.assets.get(definition.resource());return resources.geometry.computeIfAbsent(new GeometryKey(asset.sha256(),definition),key->JEDTemplateFootprint.compute(definition,asset,resources.registries));}
    private static JEDStructurePlan plan(Assets resources,JsonObject placement,BlockPos origin,int bottom,int top){var definition=JEDStructureDefinition.decode(placement,"authored structure");return JEDStructurePlan.decode(definition,resources.assets.get(definition.resource()),origin,resources.registries,bottom,top);}
    @Override protected Optional<GenerationStub> findGenerationPoint(GenerationContext c){
        var resources=STATES.get(c.randomState());if(resources==null)throw new JEDDefinitionException("Authored structure assets are not bound to this dimension's RandomState");var footprint=geometry(resources,definition);footprint.requireNativeRange(this::adjustBoundingBox,definition.asset().toString());
        int x=c.chunkPos().getMiddleBlockX(),z=c.chunkPos().getMiddleBlockZ(),bottom=c.heightAccessor().getMinBuildHeight(),top=c.heightAccessor().getMaxBuildHeight();int y;
        if(mode.equals("surface"))y=c.chunkGenerator().getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,c.heightAccessor(),c.randomState());
        else if(mode.equals("fixed"))y=minY;
        else{int low=Math.max(bottom,minY),high=Math.min(top-1,maxY);if(low>high)return Optional.empty();var column=c.chunkGenerator().getBaseColumn(x,z,c.heightAccessor(),c.randomState());if(mode.equals("range"))y=low+c.random().nextInt(high-low+1);else{int highestSolid=bottom-1;for(int cy=top-1;cy>=bottom;cy--)if(Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(column.getBlock(cy))){highestSolid=cy;break;}var candidates=new ArrayList<Integer>();for(int cy=low;cy<=high;cy++){var block=column.getBlock(cy);boolean eligible=mode.equals("underground")?cy<highestSolid&&Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(block):block.isAir()&&(mode.equals("ceiling")?Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(column.getBlock(cy+1)):cy<highestSolid&&Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(column.getBlock(cy-1)));if(eligible)candidates.add(cy);}if(candidates.isEmpty())return Optional.empty();y=candidates.get(c.random().nextInt(candidates.size()));}}
        BlockPos anchor=new BlockPos(x,y,z);if(mode.equals("ceiling"))anchor=anchor.below(footprint.height()-1);if(!footprint.fits(anchor,bottom,top))return Optional.empty();
        var finalAnchor=anchor;var prepared=plan(resources,placement,anchor,bottom,top);return Optional.of(new GenerationStub(anchor,b->b.addPiece(new Piece(placement,finalAnchor,bottom,top,prepared,footprint))));
    }
    public static final class Piece extends StructurePiece {
        final JsonObject placement;final BlockPos anchor;final int bottom,top;final JEDStructurePlan plan;final StructureTemplate entities;
        Piece(JsonObject placement,BlockPos anchor,int bottom,int top,JEDStructurePlan plan,JEDTemplateFootprint footprint){super(JEDGeneratorFactory.TEMPLATE_PIECE.get(),0,footprint.bounds(anchor,plan.bounds()));this.placement=placement.deepCopy();this.anchor=anchor;this.bottom=bottom;this.top=top;this.plan=plan;this.entities=entities(plan);}
        public Piece(StructurePieceSerializationContext context,CompoundTag tag){super(JEDGeneratorFactory.TEMPLATE_PIECE.get(),tag);placement=JEDJson.object(JsonParser.parseString(tag.getString("JEDPlacement")),"saved structure placement");int[] a=tag.getIntArray("JEDAnchor");if(a.length!=3)throw new JEDDefinitionException("Invalid saved structure anchor");anchor=new BlockPos(a[0],a[1],a[2]);bottom=tag.getInt("JEDBottom");top=tag.getInt("JEDTop");var resources=CONTEXTS.get(context);if(resources==null)throw new JEDDefinitionException("Unbound native structure context; matching JED and Arbitrage builds are required");plan=plan(resources,placement,anchor,bottom,top);var definition=JEDStructureDefinition.decode(placement,"saved authored structure");boundingBox=geometry(resources,definition).bounds(anchor,plan.bounds());entities=entities(plan);}
        @Override protected void addAdditionalSaveData(StructurePieceSerializationContext c,CompoundTag tag){tag.putString("JEDPlacement",placement.toString());tag.putIntArray("JEDAnchor",new int[]{anchor.getX(),anchor.getY(),anchor.getZ()});tag.putInt("JEDBottom",bottom);tag.putInt("JEDTop",top);}
        @Override public void postProcess(WorldGenLevel level,StructureManager structures,ChunkGenerator generator,RandomSource random,BoundingBox clip,ChunkPos chunk,BlockPos pivot){
            var definition=JEDStructureDefinition.decode(placement,"authored structure");for(var template:plan.batches())if(!template.placeInWorld(level,plan.origin(),plan.origin(),JEDStructurePlan.settings(definition).setBoundingBox(clip),random,18))throw new JEDDefinitionException("Native authored structure placement failed: "+plan.source());
            if(!plan.entities().isEmpty()&&!entities.placeInWorld(level,plan.origin(),plan.origin(),JEDStructurePlan.settings(definition).setIgnoreEntities(false).setBoundingBox(clip),random,18))throw new JEDDefinitionException("Native authored entity placement failed: "+plan.source());
            for(var cell:plan.biomes())if((cell.x()>>2)==chunk.x&&(cell.z()>>2)==chunk.z){var access=level.getChunk(chunk.x,chunk.z);int section=access.getSectionIndex(cell.y()*4);if(section<0||section>=access.getSectionsCount())continue;var selected=level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,cell.biome()));access.getSection(section).fillBiomesFromNoise((x,y,z,s)->x==cell.x()&&y==cell.y()&&z==cell.z()?selected:access.getSection(section).getNoiseBiome(x&3,y&3,z&3),level.getLevel().getChunkSource().randomState().sampler(),chunk.x*4,Math.floorDiv(cell.y(),4)*4,chunk.z*4);}
        }
        private static StructureTemplate entities(JEDStructurePlan plan){
            var tag=new CompoundTag();var size=new ListTag();for(int i=0;i<3;i++)size.add(IntTag.valueOf(1));tag.put("size",size);var palette=new ListTag();var air=new CompoundTag();air.putString("Name","minecraft:air");palette.add(air);tag.put("palette",palette);tag.put("blocks",new ListTag());var entities=new ListTag();
            for(var info:plan.entities()){var entry=new CompoundTag();var pos=new ListTag();pos.add(DoubleTag.valueOf(info.pos.x));pos.add(DoubleTag.valueOf(info.pos.y));pos.add(DoubleTag.valueOf(info.pos.z));entry.put("pos",pos);var block=new ListTag();block.add(IntTag.valueOf(info.blockPos.getX()));block.add(IntTag.valueOf(info.blockPos.getY()));block.add(IntTag.valueOf(info.blockPos.getZ()));entry.put("blockPos",block);var nbt=info.nbt.copy();stripUUID(nbt);entry.put("nbt",nbt);entities.add(entry);}tag.put("entities",entities);var template=new StructureTemplate();template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),tag);return template;
        }
        private static void stripUUID(CompoundTag tag){tag.remove("UUID");for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))stripUUID((CompoundTag)child);}
    }
}
