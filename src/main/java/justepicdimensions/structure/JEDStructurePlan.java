package justepicdimensions.structure;

import java.io.*;
import java.util.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.*;
import net.minecraft.util.datafix.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.phys.Vec3;

/** Fully validated detached input, divided into bounded native placement batches. */
public record JEDStructurePlan(BlockPos origin, BoundingBox bounds, List<StructureTemplate> batches,
                               List<BlockPos> blocks, List<StructureTemplate.StructureEntityInfo> entities,
                               List<BiomeCell> biomes, List<net.minecraft.world.level.ChunkPos> chunks,
                               it.unimi.dsi.fastutil.longs.LongSet tickIsolation, String source, String hash) {
    public record BiomeCell(int x,int y,int z,ResourceLocation biome){}
    /** Copy dynamic biome lookups on the server thread; runtime registry appends cannot race a decoder. */
    public static HolderLookup.Provider snapshot(HolderLookup.Provider source){
        var biomes=source.lookupOrThrow(Registries.BIOME);
        var entries=biomes.listElements().collect(java.util.stream.Collectors.toUnmodifiableMap(Holder.Reference::key,java.util.function.Function.identity()));
        var fixed=new HolderLookup.RegistryLookup.Delegate<net.minecraft.world.level.biome.Biome>(){
            public HolderLookup.RegistryLookup<net.minecraft.world.level.biome.Biome> parent(){return biomes;}
            @Override public Optional<Holder.Reference<net.minecraft.world.level.biome.Biome>> get(net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> key){return Optional.ofNullable(entries.get(key));}
            @Override public java.util.stream.Stream<Holder.Reference<net.minecraft.world.level.biome.Biome>> listElements(){return entries.values().stream();}
            @Override public Optional<HolderSet.Named<net.minecraft.world.level.biome.Biome>> get(net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome> tag){return Optional.empty();}
            @Override public java.util.stream.Stream<HolderSet.Named<net.minecraft.world.level.biome.Biome>> listTags(){return java.util.stream.Stream.empty();}
        };
        return HolderLookup.Provider.create(java.util.stream.Stream.of(source.lookupOrThrow(Registries.BLOCK),fixed));
    }
    /** Validate asset content independently of its chosen worldgen position or rotation. */
    public record ValidatedAsset(JEDStructurePlan plan,BlockPos embeddedOffset){}
    public static ValidatedAsset validateAsset(JEDStructureDefinition definition,JEDStructureAssets.Asset asset,HolderLookup.Provider registries){
        BlockPos embeddedOffset=BlockPos.ZERO;long lowest=0;
        try{
            var tag=NbtIo.readCompressed(new ByteArrayInputStream(asset.bytes()),NbtAccounter.unlimitedHeap());
            if(definition.asset().getPath().endsWith(".schem")){
                var schematic=JEDSchematicReader.decode(tag,asset.source());embeddedOffset=schematic.offset();
                for(var entity:schematic.entities()){var pos=entity.getList("Pos",Tag.TAG_DOUBLE);if(pos.size()==3&&Double.isFinite(pos.getDouble(1)))lowest=Math.min(lowest,(long)Math.floor(pos.getDouble(1)));}
            }else for(var item:tag.getList("entities",Tag.TAG_COMPOUND)){
                var entity=(CompoundTag)item;var pos=entity.getList("pos",Tag.TAG_DOUBLE);var block=entity.getList("blockPos",Tag.TAG_INT);
                if(pos.size()==3&&Double.isFinite(pos.getDouble(1)))lowest=Math.min(lowest,(long)Math.floor(pos.getDouble(1)));if(block.size()==3)lowest=Math.min(lowest,block.getInt(1));
            }
        }catch(IOException e){throw new JEDDefinitionException("Cannot decode structure "+asset.source(),e);}
        // Include entities below local Y=0; a neutral position must not make valid content cross the test world's floor.
        if(lowest < -4063)throw invalid(asset,"entity footprint exceeds world build height");
        long neutralY=-2032L-lowest-embeddedOffset.getY();if(neutralY<Integer.MIN_VALUE||neutralY>Integer.MAX_VALUE)throw invalid(asset,"placement offset exceeds world bounds");
        var neutral=new JEDStructureDefinition(definition.asset(),Rotation.NONE,Mirror.NONE,true,BlockPos.ZERO,0,definition.pasteBiomes());
        return new ValidatedAsset(decode(neutral,asset,new BlockPos(0,(int)neutralY,0),registries,-2032,2032),embeddedOffset);
    }
    public static JEDStructurePlan decode(JEDStructureDefinition definition,JEDStructureAssets.Asset asset,
                                          BlockPos spawn,HolderLookup.Provider registries,int minY,int maxY) {
        try {
            CompoundTag tag=NbtIo.readCompressed(new ByteArrayInputStream(asset.bytes()),NbtAccounter.unlimitedHeap());
            JEDSchematicReader.Schematic schematic=null;
            if(definition.asset().getPath().endsWith(".schem")){schematic=JEDSchematicReader.decode(tag,asset.source());tag=nativeTag(schematic,registries);}
            int version=NbtUtils.getDataVersion(tag,500);
            if(version>SharedConstants.getCurrentVersion().getDataVersion().getVersion())throw invalid(asset,"asset was saved by a newer Minecraft version");
            tag=DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(),tag,version);
            var size=tag.getList("size",Tag.TAG_INT);
            if(size.size()!=3||size.getInt(0)<=0||size.getInt(1)<=0||size.getInt(2)<=0)throw invalid(asset,"size must contain three positive integers");
            Vec3i dimensions=new Vec3i(size.getInt(0),size.getInt(1),size.getInt(2));
            if(dimensions.getX()>59999969||dimensions.getZ()>59999969||dimensions.getY()>(long)maxY-minY)throw invalid(asset,"declared dimensions exceed world build bounds");
            var settings=settings(definition);
            BlockPos offset=schematic==null?BlockPos.ZERO:schematic.offset();
            if(Math.abs((long)offset.getX())>59999969||Math.abs((long)offset.getY())>59999969||Math.abs((long)offset.getZ())>59999969)throw invalid(asset,"schematic offset exceeds world bounds");
            var transformedOffset=StructureTemplate.transform(offset,definition.mirror(),definition.rotation(),BlockPos.ZERO);
            BlockPos origin=new BlockPos(Math.addExact(Math.addExact(spawn.getX(),definition.offset().getX()),transformedOffset.getX()),Math.addExact(Math.addExact(spawn.getY(),definition.offset().getY()),transformedOffset.getY()),Math.addExact(Math.addExact(spawn.getZ(),definition.offset().getZ()),transformedOffset.getZ()));
            if(Math.abs((long)origin.getX())>89999953||Math.abs((long)origin.getZ())>89999953)throw invalid(asset,"placement origin exceeds world bounds");
            var empty=new StructureTemplate();var shell=tag.copy();shell.put("blocks",new ListTag());shell.put("entities",new ListTag());
            empty.load(registries.lookupOrThrow(Registries.BLOCK),shell);
            BoundingBox bounds=empty.getBoundingBox(settings,origin);
            if(definition.centered()){origin=origin.offset(-Math.floorDiv(bounds.minX()+bounds.maxX(),2)+spawn.getX()+definition.offset().getX(),0,-Math.floorDiv(bounds.minZ()+bounds.maxZ(),2)+spawn.getZ()+definition.offset().getZ());bounds=empty.getBoundingBox(settings,origin);}
            if(bounds.minY()<minY||bounds.maxY()>=maxY||bounds.minX()<-29999984||bounds.maxX()>29999984||bounds.minZ()<-29999984||bounds.maxZ()>29999984)throw invalid(asset,"transformed structure lies outside world build bounds");
            ListTag palette=tag.getList("palette",Tag.TAG_COMPOUND);
            if(tag.contains("palettes")){var choices=tag.getList("palettes",Tag.TAG_LIST);if(choices.isEmpty())throw invalid(asset,"palettes is empty");palette=(ListTag)choices.get(settings.getRandom(origin).nextInt(choices.size()));tag.remove("palettes");tag.put("palette",palette);}
            if(palette.isEmpty()&&!tag.getList("blocks",Tag.TAG_COMPOUND).isEmpty())throw invalid(asset,"block palette is missing");
            for(Tag item:palette)validateState((CompoundTag)item,registries,asset);
            var positions=new ArrayList<BlockPos>();var batches=new ArrayList<StructureTemplate>();var segment=new ListTag();var seen=new HashSet<BlockPos>();
            for(Tag item:tag.getList("blocks",Tag.TAG_COMPOUND)){
                var block=(CompoundTag)item;int state=block.getInt("state");var pos=block.getList("pos",Tag.TAG_INT);
                if(!block.contains("state",Tag.TAG_INT)||state<0||state>=palette.size()||pos.size()!=3)throw invalid(asset,"invalid block palette index or position");
                var local=new BlockPos(pos.getInt(0),pos.getInt(1),pos.getInt(2));
                if(local.getX()<0||local.getX()>=dimensions.getX()||local.getY()<0||local.getY()>=dimensions.getY()||local.getZ()<0||local.getZ()>=dimensions.getZ()||!seen.add(local))throw invalid(asset,"duplicate or out-of-bounds block position");
                if(block.contains("nbt")){
                    if(!block.contains("nbt",Tag.TAG_COMPOUND))throw invalid(asset,"block entity data must be a compound");
                    var be=block.getCompound("nbt");var id=ResourceLocation.tryParse(be.getString("id"));if(id==null||!BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id))throw invalid(asset,"unknown block entity "+be.getString("id"));
                    var type=BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);var blockState=NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK),palette.getCompound(state));if(!type.isValid(blockState))throw invalid(asset,"block entity "+id+" does not match its block state");
                }
                if(palette.getCompound(state).getString("Name").equals("minecraft:structure_void"))continue;
                segment.add(block.copy());positions.add(StructureTemplate.transform(local,definition.mirror(),definition.rotation(),BlockPos.ZERO).offset(origin));
                if(segment.size()==128){batches.add(batch(tag,segment,registries));segment=new ListTag();}
            }
            if(!segment.isEmpty())batches.add(batch(tag,segment,registries));
            var entities=new ArrayList<StructureTemplate.StructureEntityInfo>();
            for(Tag item:tag.getList("entities",Tag.TAG_COMPOUND)){
                var entity=(CompoundTag)item;var pos=entity.getList("pos",Tag.TAG_DOUBLE);var block=entity.getList("blockPos",Tag.TAG_INT);
                if(pos.size()!=3||block.size()!=3||!entity.contains("nbt",Tag.TAG_COMPOUND))throw invalid(asset,"invalid entity position or payload");
                if(!Double.isFinite(pos.getDouble(0))||!Double.isFinite(pos.getDouble(1))||!Double.isFinite(pos.getDouble(2)))throw invalid(asset,"nonfinite entity position");
                validateEntity(entity.getCompound("nbt"),asset);
                var info=new StructureTemplate.StructureEntityInfo(new Vec3(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2)),new BlockPos(block.getInt(0),block.getInt(1),block.getInt(2)),entity.getCompound("nbt").copy());
                var transformed=StructureTemplate.transform(info.pos,definition.mirror(),definition.rotation(),BlockPos.ZERO).add(Vec3.atLowerCornerOf(origin));
                if(transformed.y<minY||transformed.y>=maxY||Math.abs(transformed.x)>29999984||Math.abs(transformed.z)>29999984)throw invalid(asset,"transformed entity lies outside world bounds");
                entities.add(info);
            }
            var biomeCells=new ArrayList<BiomeCell>();
            if(schematic!=null&&definition.pasteBiomes()&&schematic.biomes()!=null){
                var data=schematic.biomes();int[] values=data.cells();var inverse=inverse(definition);
                for(String name:data.palette().values()){var id=ResourceLocation.tryParse(name);if(id==null||registries.lookupOrThrow(Registries.BIOME).get(net.minecraft.resources.ResourceKey.create(Registries.BIOME,id)).isEmpty())throw invalid(asset,"unknown biome "+name);}
                for(int qy=Math.floorDiv(bounds.minY()-2,4);qy<=Math.floorDiv(bounds.maxY()-2,4);qy++)for(int qz=Math.floorDiv(bounds.minZ()-2,4);qz<=Math.floorDiv(bounds.maxZ()-2,4);qz++)for(int qx=Math.floorDiv(bounds.minX()-2,4);qx<=Math.floorDiv(bounds.maxX()-2,4);qx++){
                    BlockPos center=new BlockPos(qx*4+2,qy*4+2,qz*4+2);if(!bounds.isInside(center))continue;
                    var relative=center.subtract(origin);var unrotated=StructureTemplate.transform(relative,Mirror.NONE,inverse,BlockPos.ZERO);var local=StructureTemplate.transform(unrotated,definition.mirror(),Rotation.NONE,BlockPos.ZERO);
                    int x=local.getX(),y=local.getY(),z=local.getZ();if(x<0||x>=dimensions.getX()||y<0||y>=dimensions.getY()||z<0||z>=dimensions.getZ())continue;
                    String name=data.palette().get(values[x+z*dimensions.getX()+y*dimensions.getX()*dimensions.getZ()]);var id=ResourceLocation.tryParse(name);if(id==null||registries.lookupOrThrow(Registries.BIOME).get(net.minecraft.resources.ResourceKey.create(Registries.BIOME,id)).isEmpty())throw invalid(asset,"unknown biome "+name);
                    biomeCells.add(new BiomeCell(qx,qy,qz,id));
                }
            }
            var chunks=new TreeSet<net.minecraft.world.level.ChunkPos>(Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));int radius=definition.loadAround();
            for(int z=(bounds.minZ()-radius)>>4;z<=(bounds.maxZ()+radius)>>4;z++)for(int x=(bounds.minX()-radius)>>4;x<=(bounds.maxX()+radius)>>4;x++)chunks.add(new net.minecraft.world.level.ChunkPos(x,z));
            for(var entity:entities){var position=StructureTemplate.transform(entity.pos,definition.mirror(),definition.rotation(),BlockPos.ZERO).add(Vec3.atLowerCornerOf(origin));chunks.add(new net.minecraft.world.level.ChunkPos(BlockPos.containing(position)));}
            var edited=new HashSet<net.minecraft.world.level.ChunkPos>();for(var position:positions)edited.add(new net.minecraft.world.level.ChunkPos(position));for(var entity:entities){var position=StructureTemplate.transform(entity.pos,definition.mirror(),definition.rotation(),BlockPos.ZERO).add(Vec3.atLowerCornerOf(origin));edited.add(new net.minecraft.world.level.ChunkPos(BlockPos.containing(position)));}
            var isolation=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();for(var chunk:edited)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++)isolation.add(net.minecraft.world.level.ChunkPos.asLong(chunk.x+dx,chunk.z+dz));
            return new JEDStructurePlan(origin,bounds,List.copyOf(batches),List.copyOf(positions),List.copyOf(entities),List.copyOf(biomeCells),List.copyOf(chunks),it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(isolation),asset.source(),asset.sha256());
        }catch(IOException error){throw new JEDDefinitionException("Cannot decode structure "+asset.source(),error);}
    }
    public static StructurePlaceSettings settings(JEDStructureDefinition definition){return new StructurePlaceSettings().setRotation(definition.rotation()).setMirror(definition.mirror()).setKnownShape(true).setIgnoreEntities(true);}
    private static Rotation inverse(JEDStructureDefinition definition){return switch(definition.rotation()){case CLOCKWISE_90->Rotation.COUNTERCLOCKWISE_90;case COUNTERCLOCKWISE_90->Rotation.CLOCKWISE_90;default->definition.rotation();};}
    private static StructureTemplate batch(CompoundTag original,ListTag blocks,HolderLookup.Provider registries){var tag=new CompoundTag();tag.put("size",original.get("size").copy());tag.put("palette",original.get("palette").copy());tag.put("blocks",blocks);tag.put("entities",new ListTag());var template=new StructureTemplate();template.load(registries.lookupOrThrow(Registries.BLOCK),tag);return template;}
    private static void validateEntity(CompoundTag nbt,JEDStructureAssets.Asset asset){var id=ResourceLocation.tryParse(nbt.getString("id"));if(id==null||id.toString().equals("minecraft:player")||!BuiltInRegistries.ENTITY_TYPE.containsKey(id))throw invalid(asset,"unknown or unsupported entity "+nbt.getString("id"));for(Tag passenger:nbt.getList("Passengers",Tag.TAG_COMPOUND))validateEntity((CompoundTag)passenger,asset);}
    private static void validateState(CompoundTag state,HolderLookup.Provider registries,JEDStructureAssets.Asset asset){
        var id=ResourceLocation.tryParse(state.getString("Name"));if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))throw invalid(asset,"unknown block "+state.getString("Name"));
        var block=BuiltInRegistries.BLOCK.get(id);if(state.contains("Properties")&&!state.contains("Properties",Tag.TAG_COMPOUND))throw invalid(asset,"block properties must be a compound");
        var properties=state.getCompound("Properties");for(String key:properties.getAllKeys()){var property=block.getStateDefinition().getProperty(key);if(property==null||!properties.contains(key,Tag.TAG_STRING)||property.getValue(properties.getString(key)).isEmpty())throw invalid(asset,"invalid block property "+id+"["+key+"="+properties.getString(key)+"]");}
    }
    private static CompoundTag nativeTag(JEDSchematicReader.Schematic schematic,HolderLookup.Provider registries){
        var tag=new CompoundTag();tag.putInt("DataVersion",schematic.dataVersion());tag.put("size",ints(schematic.size().getX(),schematic.size().getY(),schematic.size().getZ()));
        var palette=new ListTag();var indices=new HashMap<Integer,Integer>();
        if(schematic.blocks()!=null)for(var entry:schematic.blocks().palette().entrySet()){
            String text=entry.getValue();int open=text.indexOf('[');String name=open<0?text:text.substring(0,open);var state=new CompoundTag();state.putString("Name",name);
            if(open>=0){if(!text.endsWith("]"))throw new JEDDefinitionException("Malformed schematic block state "+text);var props=new CompoundTag();String inside=text.substring(open+1,text.length()-1);if(!inside.isEmpty())for(String part:inside.split(",",-1)){var pair=part.split("=",-1);if(pair.length!=2||pair[0].isBlank()||pair[1].isBlank()||props.contains(pair[0]))throw new JEDDefinitionException("Malformed schematic block state "+text);props.putString(pair[0],pair[1]);}state.put("Properties",props);}
            indices.put(entry.getKey(),palette.size());palette.add(state);
        }
        tag.put("palette",palette);var blocks=new ListTag();var entitiesByPosition=new HashMap<BlockPos,CompoundTag>();for(var be:schematic.blockEntities()){int[] pos=be.getIntArray("Pos");var data=be.contains("Data")?be.getCompound("Data").copy():new CompoundTag();data.putString("id",be.getString("Id"));entitiesByPosition.put(new BlockPos(pos[0],pos[1],pos[2]),data);}
        if(schematic.blocks()!=null){int[] cells=schematic.blocks().cells();int w=schematic.size().getX(),length=schematic.size().getZ();for(int index=0;index<cells.length;index++){int x=index%w,z=index/w%length,y=index/(w*length);var item=new CompoundTag();item.putInt("state",indices.get(cells[index]));item.put("pos",ints(x,y,z));var be=entitiesByPosition.get(new BlockPos(x,y,z));if(be!=null)item.put("nbt",be);blocks.add(item);}}
        tag.put("blocks",blocks);var entities=new ListTag();for(var entity:schematic.entities()){var item=new CompoundTag();var pos=entity.getList("Pos",Tag.TAG_DOUBLE);item.put("pos",pos.copy());item.put("blockPos",ints((int)Math.floor(pos.getDouble(0)),(int)Math.floor(pos.getDouble(1)),(int)Math.floor(pos.getDouble(2))));var data=entity.contains("Data")?entity.getCompound("Data").copy():new CompoundTag();data.putString("id",entity.getString("Id"));item.put("nbt",data);entities.add(item);}tag.put("entities",entities);return tag;
    }
    private static ListTag ints(int x,int y,int z){var list=new ListTag();list.add(IntTag.valueOf(x));list.add(IntTag.valueOf(y));list.add(IntTag.valueOf(z));return list;}
    private static JEDDefinitionException invalid(JEDStructureAssets.Asset asset,String reason){return new JEDDefinitionException(asset.source()+": "+reason);}
}
