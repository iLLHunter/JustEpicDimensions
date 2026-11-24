package justepicdimensions.structure;

import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.spawn.JEDSpawnController;
import net.minecraft.Util;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned staged placement. Workers only decode detached input and read frozen registries. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDStructurePlacer {
    private static final Map<MinecraftServer,Map<ResourceLocation,Boolean>> FRESH=new IdentityHashMap<>();
    private static final Map<ServerLevel,Job> JOBS=new LinkedHashMap<>();
    private static final Map<ServerPlayer,Set<ResourceLocation>> ADMISSIONS=new IdentityHashMap<>();
    private static final TicketType<ChunkPos> TICKET=TicketType.create("jed_structure",Comparator.comparingLong(ChunkPos::toLong));
    private static int cursor;
    private static final boolean TRACE=Boolean.getBoolean("jed.structure.trace");
    private static final java.lang.management.ThreadMXBean CPU_TRACE=TRACE?java.lang.management.ManagementFactory.getThreadMXBean():null;
    public record SliceTrace(String started_at,long sequence,int phase,int batch_start,int batch_end,int entity_start,int entity_end,long wall_ns,long cpu_ns){}
    @jdk.jfr.Name("justepicdimensions.StructureSlice")
    @jdk.jfr.Label("JED structure preparation slice")
    public static final class SliceEvent extends jdk.jfr.Event {
        public String dimension;public long sequence;public int phase;public long wallNanos;
    }
    private static final ThreadLocal<Boolean> WRITING=ThreadLocal.withInitial(()->false);
    public static boolean holdChunk(ServerLevel level,long chunk){if(WRITING.get())return false;var job=JOBS.get(level);return job!=null&&!job.result.isDone()&&job.phase>=3&&job.plan!=null&&job.plan.tickIsolation().contains(chunk);}
    public static boolean holdChunk(net.minecraft.world.level.chunk.LevelChunk chunk){return chunk.getLevel() instanceof ServerLevel level&&holdChunk(level,chunk.getPos().toLong());}
    public static boolean holdEntity(Entity entity){return entity.level() instanceof ServerLevel level&&holdChunk(level,entity.chunkPosition().toLong());}
    public static void construct(MinecraftServer server,ResourceKey<Level> key){
        var path=net.minecraft.world.level.dimension.DimensionType.getStorageFolder(key,server.getWorldPath(LevelResource.ROOT));
        FRESH.computeIfAbsent(server,s->new HashMap<>()).put(key.location(),!Files.isDirectory(path.resolve("region"))||Files.exists(path.resolve(".jed-template-new")));
    }
    private static JEDStructureDefinition definition(ServerLevel level){var state=JEDDefinitionManager.state(level.getServer()).definitions().get(level.dimension().location());return state==null?null:state.spawnStructure();}
    public static CompletableFuture<Void> prepare(ServerLevel level){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Structure preparation requires the server thread");
        var store=JEDStructureStore.get(level);if(store.initialized)return CompletableFuture.completedFuture(null);
        var definition=definition(level);
        if(definition==null||store.operation==null&&!Files.exists(intent(level))&&!FRESH.getOrDefault(level.getServer(),Map.of()).getOrDefault(level.dimension().location(),false)){store.complete();return CompletableFuture.completedFuture(null);}
        var existing=JOBS.get(level);if(existing!=null)return existing.result;
        var job=new Job(level,definition);JOBS.put(level,job);return job.result;
    }
    public static void publish(MinecraftServer server){
        for(var entry:List.copyOf(JOBS.entrySet()))if(entry.getKey().getServer()==server){var job=entry.getValue();var definition=definition(entry.getKey());String hash=null;try{if(definition!=null)hash=JEDDefinitionManager.state(server).prepared().structureAssets().get(definition.resource()).sha256();}catch(JEDDefinitionException missing){}
            if(!Objects.equals(job.definition,definition)||!Objects.equals(job.hash,hash)){job.fail(new CancellationException("Structure configuration changed; retry entry"));JOBS.remove(entry.getKey(),job);}}
        var assets=JEDDefinitionManager.state(server).prepared().structureAssets();for(String conflict:assets.conflicts())LogUtils.getLogger().info("JED structure asset conflict: {}",conflict);
    }
    public static boolean ready(ServerLevel level){return JEDStructureStore.get(level).initialized;}
    public static boolean failed(MinecraftServer server,ResourceLocation id){var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));var job=level==null?null:JOBS.get(level);return job!=null&&job.failure!=null;}
    private static Path intent(ServerLevel level){return justepicdimensions.world.JEDWorldStore.file(level.getServer().getWorldPath(LevelResource.ROOT),level.dimension().location()).resolveSibling("structure_placement.json");}
    public static String error(ServerLevel level){var job=JOBS.get(level);return job!=null&&job.failure!=null?explain(job.failure):"Spawn structure preparation is still in progress for "+level.dimension().location();}
    public static String explain(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error.getMessage()==null?error.toString():error.getMessage();}
    public static void rejected(ServerPlayer player,ServerLevel target){String message="JED entry to "+target.dimension().location()+" rejected: "+error(target)+". Please report this to the server owner or pack maker.";player.sendSystemMessage(justepicdimensions.JEDText.tr("connection.entry",target.dimension().location(),JOBS.get(target)!=null&&JOBS.get(target).failure!=null?justepicdimensions.JEDText.error(JOBS.get(target).failure):Component.literal(error(target))));LogUtils.getLogger().warn("{} Player: {}",message,player.getUUID());}
    public static void admission(ServerPlayer player,ServerLevel target,CompletableFuture<Void> readiness){
        var id=target.dimension().location();var pending=ADMISSIONS.computeIfAbsent(player,p->new HashSet<>());if(!pending.add(id))return;var source=player.level();
        readiness.whenCompleteAsync((value,error)->{pending.remove(id);if(pending.isEmpty())ADMISSIONS.remove(player,pending);if(error!=null&&player.level()==source&&player.connection!=null&&player.connection.player==player&&player.connection.isAcceptingMessages()){String message="JED entry to "+id+" rejected: "+explain(error)+". Please report this to the server owner or pack maker.";player.sendSystemMessage(justepicdimensions.JEDText.tr("connection.entry",id,justepicdimensions.JEDText.error(error)));LogUtils.getLogger().warn("{} Player: {}",message,player.getUUID());}},player.server);
    }
    public static Map<String,Map<String,Long>> timings(MinecraftServer server){var result=new TreeMap<String,Map<String,Long>>();JOBS.forEach((level,job)->{if(level.getServer()==server){var values=new TreeMap<String,Long>();values.put("slices",job.slices);values.put("max_slice_ns",job.maxSlice);values.put("max_slice_phase",(long)job.maxSlicePhase);values.put("decode_ns",job.decodeNanos);values.put("blocks",(long)(job.plan==null?0:job.plan.blocks().size()));values.put("pending_chunks",(long)job.inFlight);values.put("complete",job.result.isDone()&&!job.result.isCompletedExceptionally()?1L:0L);for(int phase=0;phase<8;phase++){values.put("phase_"+phase+"_total_ns",job.phaseTotal[phase]);values.put("phase_"+phase+"_max_ns",job.phaseMax[phase]);}values.put("entity_load_ns",job.entityLoadNanos);values.put("light_gate_ns",job.lightGateNanos);values.put("completion_marker_ns",job.markerNanos);values.put("lease_close_ns",job.closeNanos);values.put("readiness_completion_ns",job.completionNanos);result.put(level.dimension().location().toString(),Map.copyOf(values));}});return Map.copyOf(result);}
    /** Opt-in investigation data; no change to admission, placement, tick or lighting policy. */
    public static Map<String,List<SliceTrace>> traces(MinecraftServer server){var result=new TreeMap<String,List<SliceTrace>>();JOBS.forEach((level,job)->{if(level.getServer()==server)result.put(level.dimension().location().toString(),List.copyOf(job.trace));});return Map.copyOf(result);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var jobs=JOBS.values().stream().filter(j->j.level.getServer()==event.getServer()&&!j.result.isDone()).toList();if(!jobs.isEmpty()){var job=jobs.get(Math.floorMod(cursor++,jobs.size()));try{job.slice();}catch(Throwable error){job.fail(error);}}}
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){var job=JOBS.remove(level);if(job!=null&&!job.result.isDone())job.fail(new CancellationException("Dimension unloaded during structure preparation"));}}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){for(var job:List.copyOf(JOBS.values()))if(job.level.getServer()==event.getServer()&&!job.result.isDone())job.fail(new CancellationException("Server stopping"));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){JOBS.keySet().removeIf(level->level.getServer()==event.getServer());FRESH.remove(event.getServer());ADMISSIONS.keySet().removeIf(player->player.server==event.getServer());}
    private static final class Job {
        final ServerLevel level;
        final JEDStructureDefinition definition;
        final CompletableFuture<Void> result=new CompletableFuture<>();
        final Set<ChunkPos> tickets=new LinkedHashSet<>(),changedChunks=new LinkedHashSet<>();
        final List<Entity> entities=new ArrayList<>();
        final AutoCloseable pin;
        final RandomSource random=RandomSource.create();
        UUID operation;
        String hash;Throwable failure;JEDStructurePlan plan;
        CompletableFuture<JEDStructurePlan> decoded;
        Iterator<ChunkPos> chunks,gates;
        int inFlight,phase,batch,block,entity,biome,entityChunk;
        long slices,maxSlice,decodeNanos;
        long entityLoadNanos,lightGateNanos,markerNanos,closeNanos,completionNanos;
        final long[] phaseTotal=new long[8],phaseMax=new long[8];
        final List<SliceTrace> trace=new ArrayList<>();
        int maxSlicePhase;
        boolean closed;
        Job(ServerLevel level,JEDStructureDefinition definition){
            this.level=level;this.definition=definition;pin=JEDDimensionLifecycle.pinPending(level.getServer(),level.dimension().location(),level.dimension().location());
            try{
                var store=JEDStructureStore.get(level);
                if(Files.exists(intent(level))){var tag=JEDJson.object(JEDJson.read(intent(level)),"structure placement intent");store.operation=UUID.fromString(JEDJson.string(tag.get("operation"),"structure placement operation"));store.setDirty();}
                store.begin();operation=store.operation;
                var asset=JEDDefinitionManager.state(level.getServer()).prepared().structureAssets().get(definition.resource());hash=asset.sha256();
                var spawn=JEDSpawnController.spawnPosition(level.getLevelData().getSpawnPos(),level);var registries=JEDStructurePlan.snapshot(level.registryAccess());int min=level.getMinBuildHeight(),max=level.getMaxBuildHeight();
                Path journal=intent(level);
                decoded=CompletableFuture.supplyAsync(()->{long start=System.nanoTime();try{var record=new com.google.gson.JsonObject();record.addProperty("operation",operation.toString());record.addProperty("asset",definition.asset().toString());JEDDimensionFiles.writeDurable(journal,record);return JEDStructurePlan.decode(definition,asset,spawn,registries,min,max);}finally{decodeNanos=System.nanoTime()-start;}},Util.backgroundExecutor());
            }catch(Throwable error){decoded=CompletableFuture.failedFuture(error);}
        }
        void slice(){
            int startingPhase=phase,startingBatch=batch,startingEntity=entity;
            SliceEvent event=TRACE?new SliceEvent():null;
            if(event!=null){event.dimension=level.dimension().location().toString();event.sequence=slices+1;event.phase=phase;event.begin();}
            var startedAt=TRACE?java.time.Instant.now():null;
            long cpuStart=TRACE&&CPU_TRACE.isCurrentThreadCpuTimeSupported()?CPU_TRACE.getCurrentThreadCpuTime():-1;
            long start=System.nanoTime(),deadline=start+2_000_000;slices++;
            boolean wasWriting=WRITING.get();WRITING.set(true);
            try{
                if(phase==0){if(!decoded.isDone())return;plan=decoded.join();chunks=plan.chunks().iterator();phase=1;return;}
                if(phase==1){while(inFlight<4&&chunks.hasNext()&&System.nanoTime()<deadline){var chunk=chunks.next();tickets.add(chunk);level.getChunkSource().addRegionTicket(TICKET,chunk,0,chunk);inFlight++;
                        CompletableFuture.supplyAsync(()->level.getChunkSource().getChunkFuture(chunk.x,chunk.z,ChunkStatus.FULL,true),Util.backgroundExecutor()).thenCompose(future->future).whenCompleteAsync((loaded,error)->{inFlight--;if(result.isDone())return;if(error!=null)fail(error);else if(!loaded.isSuccess())fail(new JEDDefinitionException("Chunk preparation failed for "+chunk));},level.getServer());}
                    if(chunks.hasNext()||inFlight!=0)return;while(entityChunk<plan.chunks().size()&&System.nanoTime()<deadline){if(!level.areEntitiesLoaded(plan.chunks().get(entityChunk).toLong()))return;entityChunk++;}if(entityChunk<plan.chunks().size())return;phase=2;return;}
                if(phase==2){
                    // Construct every entity before the first block edit. Invalid entity data cannot leave half a spawn.
                    while(entity<plan.entities().size()&&System.nanoTime()<deadline){var info=plan.entities().get(entity);var transformed=StructureTemplate.processEntityInfos(null,level,plan.origin(),JEDStructurePlan.settings(definition),List.of(info)).getFirst();var nbt=transformed.nbt.copy();var position=transformed.pos;var pos=new ListTag();pos.add(DoubleTag.valueOf(position.x));pos.add(DoubleTag.valueOf(position.y));pos.add(DoubleTag.valueOf(position.z));nbt.put("Pos",pos);assignUUID(nbt,"entity/"+entity);
                        long loadStart=System.nanoTime();Entity value;try{value=EntityType.loadEntityRecursive(nbt,level,e->e);}finally{entityLoadNanos+=System.nanoTime()-loadStart;}if(value==null)throw new JEDDefinitionException(plan.source()+": cannot construct entity "+nbt.getString("id"));float yaw=value.rotate(definition.rotation());yaw+=value.mirror(definition.mirror())-value.getYRot();value.moveTo(position.x,position.y,position.z,yaw,value.getXRot());entities.add(value);entity++;}
                    if(entity<plan.entities().size())return;entity=0;phase=3;return;}
                if(phase==3){while(batch<plan.batches().size()&&System.nanoTime()<deadline){if(!plan.batches().get(batch++).placeInWorld(level,plan.origin(),plan.origin(),JEDStructurePlan.settings(definition),random,18))throw new JEDDefinitionException("Native structure block placement failed: "+plan.source());}if(batch<plan.batches().size())return;phase=4;return;}
                if(phase==4){while(block<plan.blocks().size()&&System.nanoTime()<deadline){var pos=plan.blocks().get(block++);var state=level.getBlockState(pos);var updated=Block.updateFromNeighbourShapes(state,level,pos);if(updated!=state)level.setBlock(pos,updated,18);level.blockUpdated(pos,updated.getBlock());var be=level.getBlockEntity(pos);if(be!=null)be.setChanged();changedChunks.add(new ChunkPos(pos));}if(block<plan.blocks().size())return;phase=5;return;}
                if(phase==5){while(biome<plan.biomes().size()&&System.nanoTime()<deadline){var cell=plan.biomes().get(biome++);var chunk=level.getChunk(cell.x()>>2,cell.z()>>2);var section=chunk.getSection(chunk.getSectionIndex(cell.y()<<2));var selected=level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(ResourceKey.create(Registries.BIOME,cell.biome()));section.fillBiomesFromNoise((x,y,z,sampler)->x==cell.x()&&y==cell.y()&&z==cell.z()?selected:section.getNoiseBiome(x&3,y&3,z&3),level.getChunkSource().randomState().sampler(),Math.floorDiv(cell.x(),4)*4,Math.floorDiv(cell.y(),4)*4,Math.floorDiv(cell.z(),4)*4);chunk.setUnsaved(true);changedChunks.add(chunk.getPos());}if(biome<plan.biomes().size())return;phase=6;return;}
                if(phase==6){while(entity<entities.size()&&System.nanoTime()<deadline){var value=entities.get(entity++);if(level.getEntity(value.getUUID())==null&&!level.tryAddFreshEntityWithPassengers(value))throw new JEDDefinitionException("Native structure entity placement rejected: "+value.getType());}if(entity<entities.size())return;gates=changedChunks.iterator();phase=7;return;}
                if(phase==7){long part=System.nanoTime();while(gates.hasNext()&&System.nanoTime()<deadline)level.getChunkSource().chunkMap.waitForLightBeforeSending(gates.next(),1);lightGateNanos+=System.nanoTime()-part;if(gates.hasNext())return;part=System.nanoTime();JEDStructureStore.get(level).complete();markerNanos+=System.nanoTime()-part;part=System.nanoTime();close();closeNanos+=System.nanoTime()-part;part=System.nanoTime();result.complete(null);completionNanos+=System.nanoTime()-part;justepicdimensions.definition.JEDInfoLog.info(level.getServer(),"JED spawn structure {} prepared in {}",definition.asset(),level.dimension().location());}
            }finally{WRITING.set(wasWriting);long elapsed=System.nanoTime()-start;if(event!=null){event.end();event.wallNanos=elapsed;event.commit();}phaseTotal[startingPhase]+=elapsed;phaseMax[startingPhase]=Math.max(phaseMax[startingPhase],elapsed);if(elapsed>maxSlice){maxSlice=elapsed;maxSlicePhase=startingPhase;}if(TRACE&&trace.size()<4096)trace.add(new SliceTrace(startedAt.toString(),slices,startingPhase,startingBatch,batch,startingEntity,entity,elapsed,cpuStart<0?-1:CPU_TRACE.getCurrentThreadCpuTime()-cpuStart));}
        }
        void assignUUID(CompoundTag nbt,String path){nbt.putUUID("UUID",UUID.nameUUIDFromBytes((operation+"/"+path).getBytes(StandardCharsets.UTF_8)));int index=0;for(Tag value:nbt.getList("Passengers",Tag.TAG_COMPOUND))assignUUID((CompoundTag)value,path+"/"+index++);}
        void fail(Throwable error){if(result.isDone())return;failure=error;close();String message="Spawn structure "+definition.asset()+" in "+level.dimension().location()+" failed: "+explain(error);
            // Shutdown, unload and configuration changes interrupt placement; an unfinished placement resumes when the dimension next loads.
            if(error instanceof CancellationException)LogUtils.getLogger().debug("JED spawn structure {} in {} interrupted: {}",definition.asset(),level.dimension().location(),explain(error));
            else LogUtils.getLogger().error("JED {}",message,error);result.completeExceptionally(new JEDDefinitionException(message,error));}
        void close(){if(closed)return;closed=true;for(var chunk:tickets)level.getChunkSource().removeRegionTicket(TICKET,chunk,0,chunk);tickets.clear();try{pin.close();}catch(Exception error){LogUtils.getLogger().warn("JED structure lease cleanup failed",error);}}
    }
    private JEDStructurePlacer(){}
}
