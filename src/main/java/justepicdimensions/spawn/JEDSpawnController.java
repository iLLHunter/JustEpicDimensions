package justepicdimensions.spawn;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import net.minecraft.Util;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.*;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned policy and staged terrain work. No terrain access occurs on a worker. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDSpawnController {
    private static final Map<MinecraftServer,ResourceLocation> INITIAL=new IdentityHashMap<>();
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDSpawnDefinition>> POLICIES=Map.of();
    private static final Map<ServerLevel,Job> JOBS=new LinkedHashMap<>();
    private static final Map<ServerLevel,Map<String,Long>> TIMINGS=new IdentityHashMap<>();
    private static final Map<ServerPlayer,Optional<CompoundTag>> LOGIN=new IdentityHashMap<>();
    private static final Set<ServerPlayer> LOGIN_WAIT=Collections.newSetFromMap(new IdentityHashMap<>()),RESPAWN_WAIT=Collections.newSetFromMap(new IdentityHashMap<>());
    private static int cursor;
    public static JEDSpawnDefinition policy(ServerLevel level){return POLICIES.getOrDefault(level.getServer(),Map.of()).get(level.dimension().location());}
    static JEDSpawnDefinition.Search search(ServerLevel level){var policy=policy(level);return policy==null?null:policy.spawn();}
    static JEDSpawnDefinition.Sleep sleep(Level level){if(!(level instanceof ServerLevel server))return null;var p=policy(server);return p==null?null:p.sleep();}
    static boolean managed(ServerLevel level){var p=search(level);return p!=null&&!p.mode().equals("native");}
    public static boolean controlsSpawn(ServerLevel level){return managed(level);}
    public static ResourceLocation initial(JEDConfigRepository.Selection selection){var path=JEDConfigRepository.mainFile(selection);if(!java.nio.file.Files.exists(path))return null;try(var config=CommentedFileConfig.of(path)){config.load();Object value=config.getOrElse("initial_spawn_dimension","");if(!(value instanceof String text))throw new JEDDefinitionException("initial_spawn_dimension must be a namespaced string or empty");return text.isBlank()?null:JEDDimensionDefinition.id(text,"initial_spawn_dimension");}}
    public static void publish(MinecraftServer server,JEDPreparedDefinitions candidate){var id=candidate.initialSpawnDimension();if(id==null)INITIAL.remove(server);else INITIAL.put(server,id);var policies=new TreeMap<ResourceLocation,JEDSpawnDefinition>();JEDDefinitionManager.state(server).definitions().forEach((key,definition)->{var policy=definition.spawning();if(policy.spawn()!=null||policy.sleep()!=null||policy.respawn()!=null)policies.put(key,policy);});var servers=new IdentityHashMap<>(POLICIES);servers.put(server,Map.copyOf(policies));POLICIES=Collections.unmodifiableMap(servers);for(var job:List.copyOf(JOBS.values()))if(job.level.getServer()==server&&!Objects.equals(job.settings,search(job.level))){job.fail(new CancellationException("Spawn definition changed during preparation"));JOBS.remove(job.level,job);}for(var level:server.getAllLevels())if(JEDDimensionLifecycle.available(server,level.dimension().location()))prepare(level);}
    public static Map<String,Map<String,Long>> timings(MinecraftServer server){var result=new TreeMap<String,Map<String,Long>>();TIMINGS.forEach((level,time)->{if(level.getServer()==server)result.put(level.dimension().location().toString(),time);});return Map.copyOf(result);}
    public static void validate(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions){for(var definition:definitions.values()){var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(definition.id());if(stem!=null)validate(definition,stem.type().value());}}
    /** The same bounds rule can validate a detached new dimension before its registry entry is published. */
    public static void validate(JEDDimensionDefinition definition,net.minecraft.world.level.dimension.DimensionType type){var search=definition.spawning().spawn();if(search==null)return;int min=type.minY()+1,max=type.minY()+type.height()-2;if(search.y()!=null&&((long)search.y()+search.yOffset()<min||(long)search.y()+search.yOffset()>max)||search.minY()!=null&&search.minY()>max||search.maxY()!=null&&search.maxY()<min)throw new JEDDefinitionException(definition.id()+": spawn height is outside dimension bounds");}
    public static BlockPos spawnPosition(BlockPos nativePosition,Level level){if(level instanceof ServerLevel server&&managed(server)){var pos=JEDSpawnStore.get(server).position;if(pos!=null)return pos;}return nativePosition;}
    public static float spawnAngle(float nativeAngle,Level level){if(level instanceof ServerLevel server&&managed(server)){var data=JEDSpawnStore.get(server);if(data.position!=null)return data.angle;}return nativeAngle;}
    public static void setSpawn(ServerLevelData data,BlockPos pos,float angle,ServerLevel level){if(managed(level)){JEDSpawnStore.get(level).set(pos,angle);if(level.dimension()==Level.OVERWORLD||data instanceof justepicdimensions.world.JEDLevelData)data.setSpawn(pos,angle);}else data.setSpawn(pos,angle);}
    public static CompletableFuture<Void> prepare(ServerLevel level){
        return positionReady(level).thenCompose(ignored->justepicdimensions.structure.JEDStructurePlacer.prepare(level)).thenCompose(ignored->JEDSpawnChunks.ready(level));
    }
    /** Spawn-area retention waits on the position only; full arrival readiness also waits on structures and retained terrain. */
    public static CompletableFuture<Void> positionReady(ServerLevel level){return justepicdimensions.dimension.JEDPersistentChunks.ready(level).thenCompose(ignored->preparePosition(level));}
    private static CompletableFuture<Void> preparePosition(ServerLevel level){
        if(!managed(level)||JEDSpawnStore.get(level).position!=null)return CompletableFuture.completedFuture(null);
        var existing=JOBS.get(level);if(existing!=null)return existing.result;
        var job=new Job(level,search(level));JOBS.put(level,job);return job.result;
    }
    @SubscribeEvent public static void createSpawn(LevelEvent.CreateSpawnPosition event){if(event.getLevel() instanceof ServerLevel level&&managed(level))event.setCanceled(true);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void started(ServerStartedEvent event){for(var level:event.getServer().getAllLevels())prepare(level);}
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void loaded(LevelEvent.Load event){if(event.getLevel() instanceof ServerLevel level){var context=justepicdimensions.world.JEDWorldControl.context(level);if(managed(level)&&context!=null&&context.authored()&&JEDSpawnStore.get(level).position!=null){var definition=JEDDefinitionManager.state(level.getServer()).definitions().get(level.dimension().location());var root=definition.source().value().getAsJsonObject();if(root.has("state")&&root.getAsJsonObject("state").has("on_load")&&root.getAsJsonObject("state").getAsJsonObject("on_load").has("spawn"))level.setDefaultSpawnPos(level.getLevelData().getSpawnPos(),level.getLevelData().getSpawnAngle());}prepare(level);}}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var jobs=JOBS.values().stream().filter(job->job.level.getServer()==event.getServer()&&!job.result.isDone()).toList();if(jobs.isEmpty())return;var job=jobs.get(Math.floorMod(cursor++,jobs.size()));try{job.slice();}catch(Throwable error){job.fail(error);}}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){TIMINGS.remove(level);var job=JOBS.remove(level);if(job!=null&&!job.result.isDone())job.fail(new CancellationException("Spawn preparation interrupted by level unload"));}}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){JOBS.values().stream().filter(job->job.level.getServer()==event.getServer()).toList().forEach(job->job.fail(new CancellationException("Server stopping")));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){JOBS.keySet().removeIf(level->level.getServer()==event.getServer());TIMINGS.keySet().removeIf(level->level.getServer()==event.getServer());INITIAL.remove(event.getServer());var servers=new IdentityHashMap<>(POLICIES);servers.remove(event.getServer());POLICIES=Collections.unmodifiableMap(servers);LOGIN.keySet().removeIf(p->p.server==event.getServer());LOGIN_WAIT.removeIf(p->p.server==event.getServer());RESPAWN_WAIT.removeIf(p->p.server==event.getServer());}
    static final class Job {
        private static final TicketType<ChunkPos> PREPARATION_TICKET = TicketType.create("jed_spawn_preparation",Comparator.comparingLong(ChunkPos::toLong));
        final ServerLevel level;
        final JEDSpawnDefinition.Search settings;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        final Random random;
        final AutoCloseable pin;
        final Set<ChunkPos> ownedTickets = new LinkedHashSet<>();
        final Set<ChunkPos> fallbackChunks = new LinkedHashSet<>();
        final long createdNanos = System.nanoTime();
        int x,z,y,min,max,attempts;
        /** Search columns that may still look for a native valid-spawn floor once a safe standing spot is known. */
        static final int PREFERRED_COLUMNS=16;
        final boolean anyFloor;
        BlockPos safeSpot;
        boolean column,chunkReady,fallbackReady,committed,terminal,resourcesClosed;
        ChunkPos ticket;
        BlockPos finalPosition;
        CompletableFuture<Void> fallbackLoad;
        long slices,maxSliceNanos,columnGeneration,columnWaitNanos,fallbackWaitNanos,requestNanos,commitNanos,blockUpdateNanos,publishNanos,bonusChestNanos,cleanupNanos;

        Job(ServerLevel level,JEDSpawnDefinition.Search settings){
            this.level=level;this.settings=settings;random=new Random(level.getSeed());
            x=settings.x()==null?8:settings.x();z=settings.z()==null?8:settings.z();
            // Flat floors are uniform authored blocks, so the native grass/podzol preference would only fail slowly there.
            anyFloor=settings.mode().equals("cavern")||(settings.ignoreSuitability()!=null?settings.ignoreSuitability():flat(level.getChunkSource().getGenerator()));
            if(settings.mode().equals("none")){x=level.getLevelData().getSpawnPos().getX();z=level.getLevelData().getSpawnPos().getZ();}
            int defaultMin=level.getMinBuildHeight()+1,defaultMax=level.getMaxBuildHeight()-2;
            if(settings.mode().equals("cavern")&&level.dimensionType().hasCeiling()){defaultMin=level.getMinBuildHeight()+30;defaultMax=Math.min(defaultMax,level.getMinBuildHeight()+level.dimensionType().logicalHeight()-8);}
            min=Math.max(level.getMinBuildHeight()+1,settings.minY()==null?defaultMin:settings.minY());max=Math.min(level.getMaxBuildHeight()-2,settings.maxY()==null?defaultMax:settings.maxY());
            if(min>max)throw new JEDDefinitionException(level.dimension().location()+": spawn height range is outside this dimension");
            if(settings.mode().equals("fixed")&&(settings.y()<level.getMinBuildHeight()+1||settings.y()>level.getMaxBuildHeight()-2))throw new JEDDefinitionException("Fixed spawn is outside build height");
            pin=JEDDimensionLifecycle.pinPending(level.getServer(),level.dimension().location(),level.dimension().location());
            result.whenCompleteAsync((v,error)->{if(!terminal)fail(error==null?new CancellationException("Spawn preparation completed externally"):error);},level.getServer());
        }
        static boolean flat(net.minecraft.world.level.chunk.ChunkGenerator generator){
            return generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource||generator instanceof justepicdimensions.worldgen.JEDFlatGenerator;
        }
        void slice(){
            if(terminal)return;
            long start=System.nanoTime();slices++;
            try{
                // A command or another owner may supersede selection while native futures are pending.
                if(JEDSpawnStore.get(level).position!=null){succeed();return;}
                if(finalPosition!=null){if(fallbackReady)commit(finalPosition);return;}
                if(settings.mode().equals("none")&&!settings.fallback()){finish(level.getLevelData().getSpawnPos());return;}
                if(!column){
                    column=true;chunkReady=false;ticket=new ChunkPos(x>>4,z>>4);
                    var current=ticket;long generation=++columnGeneration,requested=System.nanoTime();
                    long requestStart=System.nanoTime();
                    try{
                        retain(current);
                        request(current).whenCompleteAsync((v,error)->{
                            if(terminal||generation!=columnGeneration)return;
                            columnWaitNanos+=System.nanoTime()-requested;
                            if(error!=null)fail(error);else chunkReady=true;
                        },level.getServer());
                    }finally{requestNanos+=System.nanoTime()-requestStart;}
                    y=Integer.MIN_VALUE;return;
                }
                if(!chunkReady)return;
                if(settings.mode().equals("none")){finish(level.getLevelData().getSpawnPos());return;}
                if(settings.mode().equals("fixed")){finish(new BlockPos(x,settings.y(),z));return;}
                if(y==Integer.MIN_VALUE)y=settings.mode().equals("cavern")?max:surfaceStart(level,x,z,max);
                for(int blocks=0;y>=min&&blocks<512&&System.nanoTime()-start<2_000_000L;blocks++,y--){
                    var pos=new BlockPos(x,y,z);
                    if(!level.getWorldBorder().isWithinBounds(pos))continue;
                    if(suitable(level,pos,anyFloor)){finish(pos);return;}
                    // Remember the first clear, solid, fluid-free spot in case no native valid-spawn floor is nearby.
                    if(!anyFloor&&safeSpot==null&&suitable(level,pos,true))safeSpot=pos.immutable();
                }
                if(y>=min)return;
                releaseTicket();column=false;attempts++;
                // Bound chunk generation: once a safe spot exists, stop preferring native spawn blocks after a few columns.
                if(safeSpot!=null&&attempts>=PREFERRED_COLUMNS){finish(safeSpot);return;}
                if(settings.x()!=null||attempts>=(settings.mode().equals("cavern")?200:1000)){
                    if(safeSpot!=null){finish(safeSpot);return;}
                    var base=level.getLevelData().getSpawnPos();finish(new BlockPos(settings.x()==null?x:settings.x(),Math.max(min,Math.min(max,base.getY())),settings.z()==null?z:settings.z()));return;
                }
                x+=random.nextInt(32)-random.nextInt(32);z+=random.nextInt(32)-random.nextInt(32);
            }finally{
                maxSliceNanos=Math.max(maxSliceNanos,System.nanoTime()-start);
                if(committed){
                    var time=new LinkedHashMap<String,Long>();
                    time.put("slices",slices);time.put("max_server_slice_ns",maxSliceNanos);time.put("columns",(long)attempts+1);
                    time.put("column_wait_ns",columnWaitNanos);time.put("fallback_wait_ns",fallbackWaitNanos);time.put("fallback_chunks",(long)fallbackChunks.size());
                    time.put("request_ns",requestNanos);time.put("native_commit_ns",commitNanos);time.put("block_update_ns",blockUpdateNanos);time.put("publish_ns",publishNanos);
                    time.put("bonus_chest_ns",bonusChestNanos);time.put("cleanup_ns",cleanupNanos);time.put("elapsed_ns",System.nanoTime()-createdNanos);
                    TIMINGS.put(level,Map.copyOf(time));
                    justepicdimensions.definition.JEDInfoLog.info(level.getServer(),"JED spawn {} prepared: {} slices, maximum measured slice {} ms; fallback {} chunks / {} ms awaited, native commit {} ms",level.dimension().location(),slices,maxSliceNanos/1_000_000.0,fallbackChunks.size(),fallbackWaitNanos/1_000_000.0,commitNanos/1_000_000.0);
                }
            }
        }
        /** The off-thread entry schedules native work without managedBlock on the server thread. */
        CompletableFuture<Void> request(ChunkPos chunk){
            return CompletableFuture.supplyAsync(()->level.getChunkSource().getChunkFuture(chunk.x,chunk.z,ChunkStatus.FULL,true),Util.backgroundExecutor())
                .thenCompose(future->future).thenAccept(value->{if(!value.isSuccess())throw new IllegalStateException("Native spawn chunk failed: "+chunk);});
        }
        void retain(ChunkPos chunk){if(ownedTickets.add(chunk))level.getChunkSource().addRegionTicket(PREPARATION_TICKET,chunk,0,chunk);}
        void finish(BlockPos pos){
            if(settings.yOffset()!=0){
                long height=(long)pos.getY()+settings.yOffset();
                if(height<level.getMinBuildHeight()+1||height>level.getMaxBuildHeight()-2)throw new JEDDefinitionException("Spawn height offset is outside dimension bounds: "+level.dimension().location());
                pos=new BlockPos(pos.getX(),(int)height,pos.getZ());
            }
            if(!settings.fallback()||!level.isEmptyBlock(pos.below())){commit(pos);return;}
            finalPosition=pos.immutable();
            // This is a 3x3 BLOCK footprint: at most four chunks, including the selected column.
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)fallbackChunks.add(new ChunkPos((pos.getX()+dx)>>4,(pos.getZ()+dz)>>4));
            long requested=System.nanoTime(),requestStart=requested;
            try{
                var futures=new ArrayList<CompletableFuture<Void>>();
                for(var chunk:fallbackChunks){retain(chunk);futures.add(request(chunk));}
                // Transfer the selected-column ticket into the commit footprint before releasing it.
                releaseTicket();
                fallbackLoad=CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
                fallbackLoad.whenCompleteAsync((v,error)->{
                    if(terminal)return;
                    fallbackWaitNanos=System.nanoTime()-requested;
                    if(error!=null)fail(error);else fallbackReady=true;
                },level.getServer());
            }finally{requestNanos+=System.nanoTime()-requestStart;}
        }
        void commit(BlockPos pos){
            long start=System.nanoTime();
            try{
                // Preparation can span ticks. Never overwrite a floor added during that interval.
                if(settings.fallback()&&level.isEmptyBlock(pos.below())){long blockStart=System.nanoTime();try{level.setBlockAndUpdate(pos.below(),Blocks.GLASS.defaultBlockState());}finally{blockUpdateNanos+=System.nanoTime()-blockStart;}}
                long publishStart=System.nanoTime();
                try{if(!level.getWorldBorder().isWithinBounds(pos))level.getWorldBorder().setCenter(pos.getX(),pos.getZ());JEDSpawnChunks.publishSpawn(level,pos,settings.angle());JEDSpawnStore.get(level).set(pos,settings.angle());justepicdimensions.world.JEDBorderController.ready(level);}finally{publishNanos+=System.nanoTime()-publishStart;}
                if(level.dimension()==Level.OVERWORLD&&level.getServer().getWorldData().worldGenOptions().generateBonusChest()){
                    long bonusStart=System.nanoTime();try{net.minecraft.world.level.levelgen.feature.Feature.BONUS_CHEST.place(net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration.INSTANCE,level,level.getChunkSource().getGenerator(),level.random,pos);}finally{bonusChestNanos+=System.nanoTime()-bonusStart;}
                }
                committed=true;succeed();
            }finally{commitNanos+=System.nanoTime()-start;}
        }
        void releaseTicket(){
            columnGeneration++;
            if(ticket!=null){if(!fallbackChunks.contains(ticket)&&ownedTickets.remove(ticket))level.getChunkSource().removeRegionTicket(PREPARATION_TICKET,ticket,0,ticket);ticket=null;}
        }
        void releaseResources(){
            if(resourcesClosed)return;
            resourcesClosed=true;long start=System.nanoTime();
            try{for(var chunk:ownedTickets)level.getChunkSource().removeRegionTicket(PREPARATION_TICKET,chunk,0,chunk);ownedTickets.clear();ticket=null;close(pin);}finally{cleanupNanos+=System.nanoTime()-start;}
        }
        void succeed(){terminal=true;releaseResources();JOBS.remove(level,this);level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount(),()->result.complete(null)));}
        void fail(Throwable error){if(terminal)return;terminal=true;releaseResources();JOBS.remove(level,this);result.completeExceptionally(error);
            // Shutdown, unload and superseded work are expected interruptions; selection resumes on the next load.
            if(error instanceof CancellationException)LogUtils.getLogger().debug("JED spawn preparation for {} interrupted: {}",level.dimension().location(),error.getMessage());
            else LogUtils.getLogger().error("JED spawn preparation failed for {}",level.dimension().location(),error);}
    }

    /** Direct placement at a world spawn JED did not select (login redirect, lifecycle relocation). A secondary dimension's
     * native spawn is the Overworld's coordinate, so it gets native first-join adjustment; JED-selected spawns and the
     * Overworld's own spawn are used as they are. */
    public static BlockPos arrivalSpawn(ServerPlayer player,ServerLevel level){var spawn=level.getSharedSpawnPos();return managed(level)||level.dimension()==Level.OVERWORLD?spawn:player.adjustSpawnLocation(level,spawn);}
    /** First height a downward surface search tests. In a ceiling dimension the topmost block of a column is the roof,
     * so the search starts inside it and can only stand on floors beneath it. */
    public static int surfaceStart(ServerLevel level,int x,int z,int max){int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING,x,z);return Math.min(max,level.dimensionType().hasCeiling()?top-1:top+1);}
    public static boolean suitable(ServerLevel level,BlockPos pos,boolean ignoreSuitability){var floor=level.getBlockState(pos.below());return Block.isFaceFull(floor.getCollisionShape(level,pos.below()),Direction.UP)&&(ignoreSuitability||floor.is(BlockTags.VALID_SPAWN))&&level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()&&level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty()&&level.getFluidState(pos).isEmpty()&&level.getFluidState(pos.above()).isEmpty();}
    public static boolean bedWorks(boolean nativeValue,Level level){var policy=sleep(level);return policy==null||policy.mode().equals("native")?nativeValue:!policy.mode().equals("explode");}
    public static boolean sleepNatural(DimensionType type,ServerPlayer player){var policy=sleep(player.level());return policy!=null&&(policy.mode().equals("allow")||policy.mode().equals("deny"))||type.natural();}
    public static boolean denyBed(Level level,Player player,BlockPos pos){var p=sleep(level);return p!=null&&p.mode().equals("deny")&&!Boolean.TRUE.equals(p.checkpoints());}
    public static void bedCheckpoint(ServerPlayer player,ResourceKey<Level> dimension,BlockPos pos,float angle,boolean forced,boolean message){var p=sleep(player.level());boolean allowed=p==null||p.checkpoints()==null?(p==null||!p.mode().equals("deny")):p.checkpoints();if(allowed)player.setRespawnPosition(dimension,pos,angle,forced,message);}
    @SubscribeEvent public static void sleeping(CanPlayerSleepEvent event){var p=sleep(event.getLevel());if(p!=null&&(p.mode().equals("deny")||p.mode().equals("explode")))event.setProblem(Player.BedSleepingProblem.NOT_POSSIBLE_HERE);}
    @SubscribeEvent public static void continueSleeping(CanContinueSleepingEvent event){if(event.getEntity() instanceof ServerPlayer player){var p=sleep(player.level());if(p!=null&&(p.mode().equals("deny")||p.mode().equals("explode")))event.setContinueSleeping(false);}}
    public static void checkpoint(ServerPlayer player,ResourceKey<Level> changed,BlockPos requested){if(player.server.overworld()!=null)JEDPlayerStateTracker.get(player.server).record(player,requested==null?changed.location():null);}
    @SubscribeEvent public static void checkpointAvailable(PlayerSetSpawnEvent event){if(event.getEntity() instanceof ServerPlayer player&&event.getNewSpawn()!=null&&!JEDDimensionLifecycle.available(player.server,event.getSpawnLevel().location()))event.setCanceled(true);}
    public static void died(ServerPlayer player){if(justepicdimensions.world.JEDWorldControl.hardcoreDeath(player)){JEDPlayerStateTracker.get(player.server).death(player);player.setGameMode(GameType.SPECTATOR);player.serverLevel().getGameRules().getRule(GameRules.RULE_SPECTATORSGENERATECHUNKS).set(false,player.server);}}
    public static void joinFilter(net.minecraft.server.network.TextFilter filter,net.minecraft.server.network.ServerGamePacketListenerImpl listener){if(!(listener instanceof JEDPendingLoginListener)&&!(listener.getConnection().getPacketListener() instanceof JEDPendingLoginListener))filter.join();}
    @SuppressWarnings({"rawtypes","unchecked"})
    public static void loginProtocol(Connection connection,net.minecraft.network.ProtocolInfo protocol,net.minecraft.network.PacketListener listener){
        if(connection.getPacketListener() instanceof JEDPendingLoginListener){
            if(connection.getInboundProtocol().id()!=net.minecraft.network.ConnectionProtocol.PLAY||protocol.id()!=net.minecraft.network.ConnectionProtocol.PLAY)throw new IllegalStateException("JED pending login lost its play protocol");
            var pending=(JEDPendingLoginListener)connection.getPacketListener();
            if(!pending.player.server.isSameThread())throw new IllegalStateException("JED login handoff requires the server thread");
            if(!(listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl target)||target.getConnection()!=connection)throw new IllegalStateException("JED listener handoff changed connections");
            // Tick, incoming keep-alive replies and this handoff share one short lock.
            // No network/world work or cross-thread wait occurs while publishing state.
            synchronized(pending){
                if(!connection.isConnected()||pending.closing()||!((JEDSpawnCommonAccess)(Object)pending).jed$handoffTo(target))throw new java.util.concurrent.CancellationException("JED login closed before listener handoff");
                ((JEDSpawnConnectionAccess)(Object)connection).jed$replacePlayListener(protocol,listener);
            }
            target.player.getTextFilter().join();
        }else connection.setupInboundProtocol(protocol,listener);
    }
    public static Optional<CompoundTag> loginLoad(PlayerList list,ServerPlayer player){var loaded=LOGIN.remove(player);return loaded==null?list.load(player):loaded;}
    /** A loading screen may pause single player after the pending listener enters PLAY.
     * Keep native ticks running until staged spawn preparation can finish that login. */
    public static boolean pauseDuringLogin(boolean paused,MinecraftServer server){
        if(!paused)return false;
        for(var player:LOGIN_WAIT)if(player.server==server&&player.connection instanceof JEDPendingLoginListener pending
                &&!pending.closing()&&pending.getConnection().isConnected())return false;
        return true;
    }
    public static void abandonLogin(ServerPlayer player){LOGIN.remove(player);LOGIN_WAIT.remove(player);justepicdimensions.access.JEDAccessController.loginAbandoned(player);}
    public static boolean awaitLogin(Optional<CompoundTag> loaded,Connection connection,ServerPlayer player,PlayerList list,CommonListenerCookie cookie){
        ResourceLocation initial=loaded.isEmpty()?INITIAL.get(player.server):null;
        ResourceLocation saved=loaded.isPresent()?ResourceLocation.tryParse(loaded.get().getString("Dimension")):null;
        ServerLevel destination=initial!=null?destination(player.server,initial):JEDDimensionLifecycle.loginLevel(player.server,ResourceKey.create(Registries.DIMENSION,saved==null?Level.OVERWORLD.location():saved),player);
        var admission=justepicdimensions.access.JEDAccessController.loginDestination(player,destination,loaded.isEmpty());
        var future=admission.thenCompose(JEDSpawnController::prepare);
        if(!future.isDone()||future.isCompletedExceptionally()){
            if(LOGIN_WAIT.add(player)){
                LOGIN.put(player,loaded);
                var pending=new JEDPendingLoginListener(player.server,connection,player,cookie);
                var pin=JEDDimensionLifecycle.pinPending(player.server,Level.OVERWORLD.location(),destination.dimension().location());
                future.whenCompleteAsync((v,error)->{
                    LOGIN_WAIT.remove(player);
                    try{
                        if(!connection.isConnected()){LOGIN.remove(player);return;}
                        if(error!=null){LOGIN.remove(player);String message="JED entry to "+destination.dimension().location()+" rejected: "+justepicdimensions.structure.JEDStructurePlacer.explain(error)+". Please report this to the server owner or pack maker.";LogUtils.getLogger().error(message,error);pending.disconnect(justepicdimensions.JEDText.tr("connection.entry",destination.dimension().location(),justepicdimensions.JEDText.error(error)));return;}
                        var denied=list.getPlayer(player.getUUID())!=null?PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE:list.canPlayerLogin(connection.getRemoteAddress(),player.getGameProfile());
                        if(denied!=null){LOGIN.remove(player);pending.disconnect(denied);}else{player.connection=null;list.placeNewPlayer(connection,player,cookie);}
                    }catch(Throwable placementError){LOGIN.remove(player);LogUtils.getLogger().error("JED initial player placement failed",placementError);if(connection.isConnected())pending.disconnect(justepicdimensions.JEDText.tr("connection.placement",justepicdimensions.JEDText.error(placementError)));}
                    finally{close(pin);}
                },player.server);
            }
            return true;
        }
        var admitted=admission.join();
        if(initial==null&&admitted==destination)return false;
        var arrival=arrivalSpawn(player,admitted);player.setPos(arrival.getX()+.5,arrival.getY(),arrival.getZ()+.5);player.setYRot(admitted.getSharedSpawnAngle());
        player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
        var tag=loaded.map(CompoundTag::copy).orElseGet(CompoundTag::new);tag.putString("Dimension",admitted.dimension().location().toString());LOGIN.put(player,Optional.of(tag));return false;
    }
    /** Called at the native saved-dimension lookup, after the initial-entry gate chose its level. */
    public static ServerLevel loginLevel(MinecraftServer server,ResourceKey<Level> saved,ServerPlayer player){justepicdimensions.access.JEDAccessController.loginDone(player);var selected=LOGIN.remove(player);if(selected!=null&&selected.isPresent()&&selected.get().contains("Dimension"))saved=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(selected.get().getString("Dimension")));return JEDDimensionLifecycle.loginLevel(server,saved,player);}
    static ResourceLocation target(ServerPlayer player){var p=policy(player.serverLevel());var r=p==null?null:p.respawn();if(r==null)return null;if(r.destination()!=null)return r.destination();return r.allowHere()==null?null:r.allowHere()?player.level().dimension().location():Level.OVERWORLD.location();}
    static ServerLevel destination(MinecraftServer server,ResourceLocation id){if(JEDDimensionLifecycle.available(server,id)){var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,id));if(level!=null)return level;}LogUtils.getLogger().warn("JED spawn destination {} is unavailable; using its lifecycle fallback",id);var fallback=JEDDefinitionManager.state(server).definitions().get(id);var safe=fallback==null?server.overworld():server.getLevel(ResourceKey.create(Registries.DIMENSION,fallback.lifecycle().fallback()));return safe==null?server.overworld():safe;}
    private static CompletableFuture<Void> preparation(ServerPlayer player,boolean keep){var futures=new ArrayList<CompletableFuture<Void>>();var target=keep?null:target(player);if(target==null){futures.add(prepare(destination(player.server,player.getRespawnDimension().location())));futures.add(prepare(player.server.overworld()));}else{futures.add(prepare(destination(player.server,target)));var data=JEDPlayerStateTracker.get(player.server);data.record(player,null);var policy=policy(player.serverLevel()).respawn();var latest=data.player(player.getUUID()).latest;if(policy.priority().equals("saved_point_first")&&latest!=null)futures.add(prepare(destination(player.server,latest)));}return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));}
    public static boolean deferClientCommand(net.minecraft.server.network.ServerGamePacketListenerImpl connection,net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet){if(packet.getAction()!=net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN)return false;var player=connection.player;if(player.isAlive()&&!player.wonGame)return false;if(RESPAWN_WAIT.contains(player)||justepicdimensions.access.JEDAccessController.pendingRespawn(player))return true;return defer(player,preparation(player,player.wonGame),()->connection.handleClientCommand(packet));}
    public static boolean deferRespawn(PlayerList list,ServerPlayer player,boolean keep,Entity.RemovalReason reason){if(RESPAWN_WAIT.contains(player)||justepicdimensions.access.JEDAccessController.pendingRespawn(player))return true;return defer(player,preparation(player,keep),()->player.connection.player=list.respawn(player,keep,reason));}
    private static boolean defer(ServerPlayer player,CompletableFuture<Void> future,Runnable action){if(future.isDone()&&!future.isCompletedExceptionally()){future.join();return false;}RESPAWN_WAIT.add(player);var pin=JEDDimensionLifecycle.pinPending(player.server,player.level().dimension().location(),null);future.whenCompleteAsync((v,e)->{RESPAWN_WAIT.remove(player);try{if(player.connection.player==player&&player.connection.isAcceptingMessages()){if(e!=null)player.connection.disconnect(justepicdimensions.JEDText.tr("connection.respawn",justepicdimensions.JEDText.error(e)));else action.run();}}finally{close(pin);}},player.server);return true;}
    public static DimensionTransition route(ServerPlayer player,boolean keep,DimensionTransition.PostDimensionTransition post){if(keep||target(player)==null)return null;var store=JEDPlayerStateTracker.get(player.server);store.record(player,null);var data=store.player(player.getUUID());var target=destination(player.server,target(player));var policy=policy(player.serverLevel()).respawn();
        if(policy.priority().equals("saved_point_first")&&data.latest!=null){var latest=data.points.get(data.latest);if(latest!=null&&JEDDimensionLifecycle.available(player.server,latest.dimension())){var dt=point(player,latest,post);if(dt!=null)return dt;}}
        var saved=data.points.get(target.dimension().location());if(saved!=null){var dt=point(player,saved,post);if(dt!=null)return dt;}
        return managed(target)?new DimensionTransition(target,Vec3.atBottomCenterOf(target.getSharedSpawnPos()),Vec3.ZERO,target.getSharedSpawnAngle(),0,post):new DimensionTransition(target,player,post);
    }
    static DimensionTransition point(ServerPlayer player,JEDPlayerStateTracker.Point point,DimensionTransition.PostDimensionTransition post){var level=player.server.getLevel(ResourceKey.create(Registries.DIMENSION,point.dimension()));if(level==null)return null;var position=((JEDSpawnPlayerAccess)(Object)player).jed$resolveSpawn(level,point.position(),point.angle(),point.forced(),false);return position.map(p->new DimensionTransition(level,p.position(),Vec3.ZERO,p.yaw(),0,post)).orElse(null);}
    private static void close(AutoCloseable resource){try{resource.close();}catch(Exception error){throw new IllegalStateException(error);}}
    private JEDSpawnController(){}
}







