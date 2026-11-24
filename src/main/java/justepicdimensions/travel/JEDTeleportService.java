package justepicdimensions.travel;

import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.spawn.JEDSpawnController;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/** Owns preparation, never replaces native entity transfer or its mod events. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDTeleportService {
    public record Request(ResourceLocation dimension, Vec3 position, Float yaw, Float pitch, boolean spawn,
                          boolean surface, boolean bypass, boolean together, boolean automatic, JEDTravelDefinition.Rule rule) {}
    public record Destination(ResourceLocation id, Component name, boolean loaded) {}
    public record Timings(long completed,long maximumSliceNanos,long maximumPreparationNanos,int pending){}
    private static final Map<MinecraftServer,Map<ResourceLocation,JEDTravelDefinition>> POLICIES=new IdentityHashMap<>();
    private static final Map<MinecraftServer,JEDTravelOptions> OPTIONS=new IdentityHashMap<>();
    private static final Map<MinecraftServer,long[]> TIMINGS=new IdentityHashMap<>();
    private static final Map<Entity,Job> JOBS=new IdentityHashMap<>();
    private static final Map<Entity,Long> COOLDOWN=new WeakHashMap<>();
    private record Bypass(Set<Entity> entities,ResourceLocation destination){}
    private static final ThreadLocal<Bypass> BYPASS=new ThreadLocal<>();
    /** Server-owned command provenance. Packet flags cannot manufacture this capability. */
    public record Authority(net.minecraft.commands.CommandSourceStack source,ServerPlayer actor){
        public boolean valid(){return actor==null?options(source.getServer()).bypass(source):options(actor.server).bypass(actor.createCommandSourceStack());}
    }
    private static final ThreadLocal<Authority> AUTHORITY=new ThreadLocal<>();
    public static Authority authority(){return AUTHORITY.get();}
    public static boolean operatorBypass(Entity entity){var authority=AUTHORITY.get();return authority!=null&&authority.source().getServer()==entity.getServer()&&authority.valid();}
    public static boolean explicit(Entity entity,ResourceLocation destination){var value=BYPASS.get();return value!=null&&value.entities().contains(entity)&&value.destination().equals(destination);}
    private static <T>T withAuthority(Authority authority,java.util.concurrent.Callable<T> work)throws Exception{var previous=AUTHORITY.get();if(authority==null)AUTHORITY.remove();else AUTHORITY.set(authority);try{return work.call();}finally{if(previous==null)AUTHORITY.remove();else AUTHORITY.set(previous);}}
    public static CompletableFuture<Entity> travel(Entity entity,Request request,net.minecraft.commands.CommandSourceStack source){
        if(entity.getServer()!=source.getServer()||request.bypass()&&!options(source.getServer()).bypass(source))return CompletableFuture.failedFuture(new SecurityException("Operator teleport authority required"));
        var actor=source.getEntity() instanceof ServerPlayer p&&options(p.server).bypass(p.createCommandSourceStack())?p:null;
        try{return withAuthority(new Authority(source,actor),()->travel(entity,request));}catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    public static CompletableFuture<Entity> travel(Entity entity,Request request,Authority authority){try{return withAuthority(authority,()->travel(entity,request));}catch(Exception error){return CompletableFuture.failedFuture(error);}}

    private static final TicketType<ChunkPos> TICKET=TicketType.create("jed_travel",Comparator.comparingLong(ChunkPos::toLong));
    /** Columns searched around an unsafe native spawn, nearest first. Radius 8 stays inside the loaded 3x3-chunk footprint. */
    private static final int ARRIVAL_RADIUS=8;
    private static final int[][] ARRIVAL_COLUMNS=java.util.stream.IntStream.rangeClosed(-ARRIVAL_RADIUS,ARRIVAL_RADIUS).boxed()
        .flatMap(dx->java.util.stream.IntStream.rangeClosed(-ARRIVAL_RADIUS,ARRIVAL_RADIUS).mapToObj(dz->new int[]{dx,dz}))
        .sorted(Comparator.<int[]>comparingInt(o->o[0]*o[0]+o[1]*o[1]).thenComparingInt(o->o[0]).thenComparingInt(o->o[1])).toArray(int[][]::new);
    private static long cursor;
    public static void publish(MinecraftServer server,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDDimensionDefinition> definitions){var policies=new HashMap<ResourceLocation,JEDTravelDefinition>();definitions.forEach((id,value)->policies.put(id,value.travel()));POLICIES.put(server,Map.copyOf(policies));OPTIONS.put(server,candidate.travelOptions());}
    public static JEDTravelOptions options(MinecraftServer server){return OPTIONS.getOrDefault(server,JEDTravelOptions.DEFAULT);}
    public static JEDTravelDefinition policy(ServerLevel level){return policy(level.getServer(),level.dimension().location());}
    private static JEDTravelDefinition policy(MinecraftServer server,ResourceLocation id){return POLICIES.getOrDefault(server,Map.of()).getOrDefault(id,JEDTravelDefinition.DEFAULT);}
    public static Timings timings(MinecraftServer server){var values=TIMINGS.getOrDefault(server,new long[3]);return new Timings(values[0],values[1],values[2],(int)JOBS.values().stream().distinct().filter(job->job.server==server).count());}
    public static boolean permitted(MinecraftServer server,ResourceLocation from,ResourceLocation to,boolean bypass){
        if(!JEDDimensionLifecycle.available(server,to))return false;
        if(from.equals(to)||bypass)return true;
        return policy(server,from).exit()&&policy(server,to).entry();
    }
    public static List<Destination> destinations(ServerLevel source){
        var server=source.getServer();var result=new ArrayList<Destination>();
        for(var entry:server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet())
            if(permitted(server,source.dimension().location(),entry,false)&&!justepicdimensions.structure.JEDStructurePlacer.failed(server,entry))result.add(new Destination(entry,name(server,entry),server.levelKeys().contains(ResourceKey.create(Registries.DIMENSION,entry))));
        result.sort(Comparator.comparing(Destination::id));return List.copyOf(result);
    }
    public static Component name(MinecraftServer server,ResourceLocation id){
        var definition=JEDDefinitionManager.state(server).definitions().get(id);
        if(definition!=null&&definition.displayName()!=null)return Component.literal(definition.displayName());
        String fallback=switch(id.toString()){case "minecraft:overworld"->"Overworld";case "minecraft:the_nether"->"The Nether";case "minecraft:the_end"->"The End";default->id.getPath().replace('_',' ').replace('/',' ');};
        return Component.translatableWithFallback("dimension."+id.getNamespace()+"."+id.getPath().replace('/','.'),fallback);
    }
    public static CompletableFuture<Entity> travel(Entity entity,Request request){
        if(!(entity.level() instanceof ServerLevel source)||!source.getServer().isSameThread())throw new IllegalStateException("Travel must start on the server thread");
        if(JEDDimensionLifecycle.available(source.getServer(),source.dimension().location())&&!justepicdimensions.instance.JEDInstanceController.returning(entity,request.dimension())&&justepicdimensions.instance.JEDInstanceController.routingPlayer(entity,request.together())&&justepicdimensions.instance.JEDInstanceController.automatic(source.getServer(),request.dimension()))return justepicdimensions.instance.JEDInstanceController.routeTravel(entity,request);
        Entity root=request.together()?entity.getRootVehicle():entity;
        if(JOBS.containsKey(root))return CompletableFuture.failedFuture(new IllegalStateException("Travel is already pending"));
        try{
            var job=new Job(root,request);
            for(var member:job.members)if(JOBS.containsKey(member.entity))throw new IllegalStateException("A passenger already has pending travel");
            for(var member:job.members)JOBS.put(member.entity,job);
            job.result.whenComplete((value,error)->{if(job.result.isCancelled())source.getServer().execute(()->job.fail(new CancellationException("Travel cancelled")));});
            return job.result;
        }catch(Exception error){return CompletableFuture.failedFuture(error);}
    }
    public static boolean pending(Entity entity){return JOBS.containsKey(entity);}
    public static boolean cooling(Entity entity){return COOLDOWN.getOrDefault(entity,Long.MIN_VALUE)>((ServerLevel)entity.level()).getServer().getTickCount();}
    public static void automatic(Entity entity,JEDTravelDefinition.Rule rule){
        var source=(ServerLevel)entity.level();var policy=policy(source);
        var request=new Request(rule.dimension(),null,null,null,rule.mode().equals("spawn"),rule.surface(),false,policy.passengers().equals("together"),true,rule);
        travel(entity,request).whenComplete((value,error)->{if(error!=null){COOLDOWN.put(entity,(long)source.getServer().getTickCount()+Math.max(20,policy.cooldown()));LogUtils.getLogger().warn("JED automatic travel failed for {} from {} to {}: {}",entity.getUUID(),source.dimension().location(),rule.dimension(),error.toString());if(entity instanceof ServerPlayer player)player.sendSystemMessage(justepicdimensions.JEDText.tr("travel.failed",justepicdimensions.JEDText.error(error)));}});
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void restriction(EntityTravelToDimensionEvent event){
        if(event.getEntity().level() instanceof ServerLevel automaticSource&&JEDDimensionLifecycle.available(automaticSource.getServer(),automaticSource.dimension().location())&&!justepicdimensions.instance.JEDInstanceController.returning(event.getEntity(),event.getDimension().location())&&justepicdimensions.instance.JEDInstanceController.automatic(automaticSource.getServer(),event.getDimension().location())&&event.getEntity().getRootVehicle().getSelfAndPassengers().anyMatch(e->e instanceof ServerPlayer)){event.setCanceled(true);justepicdimensions.instance.JEDInstanceController.routeTravel(event.getEntity(),new Request(event.getDimension().location(),null,null,null,true,false,false,true,true,null)).whenComplete((v,error)->{if(error!=null&&event.getEntity() instanceof ServerPlayer p)p.sendSystemMessage(justepicdimensions.JEDText.error(error));});return;}
        // A vehicle carrying a player whose travel JED must hold for their client would otherwise arrive first, without that
        // rider (its passengers are moved inside its own dimension change). It waits with them instead.
        if(!(event.getEntity() instanceof ServerPlayer)&&event.getEntity().level() instanceof ServerLevel&&event.getEntity().level().dimension()!=event.getDimension()
                &&event.getEntity().getPassengersAndSelf().anyMatch(member->member instanceof ServerPlayer rider&&justepicdimensions.dimension.JEDPlayerReadiness.mustHold(rider,event.getDimension().location()))){event.setCanceled(true);return;}
        var bypass=BYPASS.get();boolean skip=bypass!=null&&bypass.entities.contains(event.getEntity())&&bypass.destination.equals(event.getDimension().location());
        if(event.getEntity().level() instanceof ServerLevel source&&!permitted(source.getServer(),source.dimension().location(),event.getDimension().location(),skip))event.setCanceled(true);
        if(!event.isCanceled()&&event.getEntity().level() instanceof ServerLevel){var decision=justepicdimensions.access.JEDAccessController.group(event.getEntity(),event.getDimension().location(),skip);if(!decision.allowed()){event.setCanceled(true);for(var member:event.getEntity().getSelfAndPassengers().toList())justepicdimensions.access.JEDAccessController.warn(member,decision);}}
        if(!event.isCanceled()&&event.getEntity().level() instanceof ServerLevel source){var target=source.getServer().levels.get(event.getDimension());if(target!=null&&!justepicdimensions.structure.JEDStructurePlacer.ready(target)){var ready=JEDSpawnController.prepare(target);if(!ready.isDone()||ready.isCompletedExceptionally()){event.setCanceled(true);if(event.getEntity() instanceof ServerPlayer player)justepicdimensions.structure.JEDStructurePlacer.admission(player,target,ready);}}}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void hold(EntityTickEvent.Pre event){
        if(holdNative(event.getEntity()))event.setCanceled(true);
    }
    public static boolean holdNative(Entity entity){if(entity.level() instanceof ServerLevel&&justepicdimensions.instance.JEDInstanceController.hold(entity))return true;if(justepicdimensions.structure.JEDStructurePlacer.holdEntity(entity))return true;var job=JOBS.get(entity);if(job==null&&entity.isPassenger())job=JOBS.get(entity.getRootVehicle());if(job==null||!job.request.automatic()||job.terminal||entity.level()!=job.source)return false;var state=job.members.stream().filter(value->value.entity==entity).findFirst().orElse(null);if(state!=null){entity.setPos(state.position);entity.setDeltaMovement(Vec3.ZERO);}return true;}
    public static void passengerTick(Entity entity){if(!holdNative(entity))entity.rideTick();}
    public static boolean floating(boolean nativeFloating,net.minecraft.server.network.ServerGamePacketListenerImpl connection){return nativeFloating&&!holdNative(connection.player);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){
        var jobs=JOBS.values().stream().distinct().filter(job->job.server==event.getServer()).toList();if(jobs.isEmpty())return;
        long start=System.nanoTime();int count=0;
        do{var job=jobs.get(Math.floorMod(cursor++,jobs.size()));try{withAuthority(job.authority,()->{job.slice();return null;});}catch(Throwable error){job.fail(error);}count++;}while(count<jobs.size()&&System.nanoTime()-start<2_000_000L);
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){for(var job:List.copyOf(new HashSet<>(JOBS.values())))if(job.server==event.getServer())job.fail(new CancellationException("Server stopping"));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){COOLDOWN.keySet().removeIf(entity->entity.level() instanceof ServerLevel level&&level.getServer()==event.getServer());POLICIES.remove(event.getServer());OPTIONS.remove(event.getServer());TIMINGS.remove(event.getServer());}

    private record Member(Entity entity,Vec3 position,Vec3 velocity,float fall,float yaw,float pitch,boolean gravity) {}
    private static final class Job {
        final Authority authority=AUTHORITY.get();
        final Entity root;final Request request;final ServerLevel source;final MinecraftServer server;
        final List<Member> members=new ArrayList<>();final CompletableFuture<Entity> result=new CompletableFuture<>();
        final Map<ServerLevel,Set<ChunkPos>> tickets=new IdentityHashMap<>();
        final long created,createdNanos=System.nanoTime();final AutoCloseable pin;final JEDTravelDefinition sourcePolicy;
        ServerLevel target;Vec3 position;CompletableFuture<Void> waiting;Footprint loading;boolean terminal,selected;int searchY=Integer.MIN_VALUE,minSurfaceY,maxSurfaceY;
        /** Set when the arrival point is a native world spawn JED did not select, so it may be unsafe in this dimension. */
        boolean nativeArrival,nativeChecked,surfaceDone,arrivalStarted,roof;BlockPos arrivalOrigin,waterTop;int arrivalColumn,arrivalY=Integer.MIN_VALUE;
        final long revision;int phase;long slices,maxSliceNanos;
        Job(Entity root,Request request)throws Exception{
            this.root=root;this.request=request;source=(ServerLevel)root.level();server=source.getServer();created=server.getTickCount();sourcePolicy=policy(source);revision=JEDDefinitionManager.state(server).revision();
            if(root.isRemoved()||!root.isAlive())throw new IllegalStateException("Entity is not alive");
            if(!server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(request.dimension()))throw justepicdimensions.JEDText.stateFailure("travel.unknown",request.dimension());
            if(!permitted(server,source.dimension().location(),request.dimension(),request.bypass()))throw justepicdimensions.JEDText.stateFailure("travel.blocked",request.dimension());
            justepicdimensions.access.JEDAccessController.require(root,request.dimension(),request.bypass(),request.together());
            var all=request.together()?root.getSelfAndPassengers().toList():List.of(root);
            for(var entity:all){if(JOBS.containsKey(entity))throw new IllegalStateException("A passenger already has pending travel");members.add(new Member(entity,entity.position(),entity.getDeltaMovement(),entity.fallDistance,entity.getYRot(),entity.getXRot(),entity.isNoGravity()));}
            for(var member:members)if(member.entity instanceof ServerPlayer player)justepicdimensions.player.JEDPlayerStateController.requireRetainedRoom(player,request.dimension());
            pin=JEDDimensionLifecycle.pinPending(server,source.dimension().location(),request.dimension());
            for(var value:members)retain(source,new ChunkPos(value.entity.blockPosition()));
        }
        Member member(Entity entity){return members.stream().filter(value->value.entity==entity).findFirst().orElseThrow();}
        void slice(){
            if(terminal)return;long start=System.nanoTime();slices++;
            try{
                if(server.getTickCount()-created>600)throw new IllegalStateException("Destination preparation timed out");
                for(var value:members)if(value.entity.isRemoved()||!value.entity.isAlive()||value.entity.level()!=source||(value.entity instanceof ServerPlayer player&&!player.connection.isAcceptingMessages()))throw new CancellationException("Traveler left, died or disconnected during preparation");
                if(JEDDefinitionManager.state(server).revision()!=revision)throw new CancellationException("Travel definitions changed during preparation; retry the trip");
                if(!permitted(server,source.dimension().location(),request.dimension(),request.bypass()))throw new CancellationException("Destination became unavailable");
                justepicdimensions.access.JEDAccessController.require(root,request.dimension(),request.bypass(),request.together());
                for(var member:members)if(member.entity instanceof ServerPlayer player)justepicdimensions.player.JEDPlayerStateController.requireRetainedRoom(player,request.dimension());
                if(phase==0){target=JEDDimensionManager.load(server,request.dimension());minSurfaceY=target.getMinBuildHeight()+1;maxSurfaceY=target.getMaxBuildHeight()-2;var definition=JEDDefinitionManager.state(server).definitions().get(request.dimension());var search=definition==null?null:definition.spawning().spawn();if(search!=null){if(search.minY()!=null)minSurfaceY=Math.max(minSurfaceY,search.minY());if(search.maxY()!=null)maxSurfaceY=Math.min(maxSurfaceY,search.maxY());}for(var value:members)retain(source,new ChunkPos(value.entity.blockPosition()));waiting=JEDSpawnController.prepare(target);phase=1;return;}
                if(waiting!=null){if(loading!=null)loading.advance();if(!waiting.isDone())return;waiting.join();waiting=null;loading=null;}
                if(phase==1){
                    var spawn=JEDSpawnController.spawnPosition(target.getLevelData().getSpawnPos(),target);Vec3 spawnPosition=Vec3.atBottomCenterOf(spawn);
                    position=request.rule()!=null?request.rule().position(member(root).position,spawnPosition,net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(source.dimensionType(),target.dimensionType())):request.spawn()?spawnPosition:request.position();
                    nativeArrival=!JEDSpawnController.controlsSpawn(target)&&(request.rule()!=null?request.rule().mode().equals("spawn"):request.spawn());
                    position=clamp(position,target);waiting=footprint(position);phase=2;return;
                }
                if(phase==2){
                    if(request.surface()&&!selected&&!surfaceDone){
                        if(!arrivalStarted){
                            if(searchY==Integer.MIN_VALUE)searchY=JEDSpawnController.surfaceStart(target,(int)Math.floor(position.x),(int)Math.floor(position.z),maxSurfaceY);
                            int blocks=0;for(;searchY>=minSurfaceY&&blocks<128&&System.nanoTime()-start<1_500_000L;searchY--,blocks++){
                                var candidate=new BlockPos((int)Math.floor(position.x),searchY,(int)Math.floor(position.z));
                                // Water stays acceptable, as documented; nothing beneath lava is.
                                if(target.getFluidState(candidate).is(net.minecraft.tags.FluidTags.LAVA)){searchY=minSurfaceY-1;break;}
                                if(suitable(candidate)){position=Vec3.atBottomCenterOf(candidate);selected=true;break;}
                            }
                            if(!selected&&searchY>=minSurfaceY)return;
                            if(!selected)beginArrival(position);
                        }
                        // The requested column had no standing spot (lava, void or solid rock): try the nearest safe surface
                        // before the configured fallback.
                        if(!selected&&!searchArrival(start))return;
                        if(!selected){surfaceDone=true;String fallback=request.rule()==null?"exact":request.rule().surfaceFallback();if(fallback.equals("cancel"))throw new IllegalStateException("No suitable destination surface");if(fallback.equals("spawn")){position=clamp(Vec3.atBottomCenterOf(JEDSpawnController.spawnPosition(target.getLevelData().getSpawnPos(),target)),target);nativeArrival=!JEDSpawnController.controlsSpawn(target);waiting=footprint(position);return;}}
                    }
                    if(nativeArrival&&!selected){
                        // A native spawn JED did not select is kept only where the travelers can already stand.
                        if(!nativeChecked){nativeChecked=true;if(standing(position))nativeArrival=false;else beginArrival(position);}
                        if(nativeArrival&&!searchArrival(start))return;
                        if(nativeArrival&&!selected){
                            var above=waterTop==null?null:Vec3.atBottomCenterOf(waterTop.above());
                            if(above!=null&&clear(above))position=above;
                            // Only travelers already flying keep the native position; the void also kills creative players.
                            else for(var value:members)if(value.entity instanceof ServerPlayer player&&!player.getAbilities().flying)
                                throw justepicdimensions.JEDText.stateFailure("travel.unsafe_arrival",request.dimension());
                        }
                    }
                    phase=3;
                }
                if(phase==3){for(var value:members)if(value.entity instanceof ServerPlayer player&&!JEDPlayerReadiness.isReady(player))return;commit();}
            }finally{maxSliceNanos=Math.max(maxSliceNanos,System.nanoTime()-start);var values=TIMINGS.computeIfAbsent(server,ignored->new long[3]);values[1]=Math.max(values[1],maxSliceNanos);}
        }
        boolean suitable(BlockPos candidate){
            if(!net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR.isOpaque().test(target.getBlockState(candidate.below())))return false;
            for(var value:members){var box=value.entity.getBoundingBox().move(Vec3.atBottomCenterOf(candidate).subtract(value.entity.position()));if(!target.noCollision(value.entity,box)||target.getBlockStates(box).anyMatch(state->state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)||state.is(net.minecraft.tags.BlockTags.FIRE)))return false;}
            return true;
        }
        void beginArrival(Vec3 around){arrivalStarted=true;arrivalOrigin=BlockPos.containing(around);arrivalColumn=0;arrivalY=Integer.MIN_VALUE;waterTop=null;}
        /** Columns around arrivalOrigin, nearest first: the first whose surface is a clear, solid, fluid-free floor sets
         * position and selected. Records the origin column's water surface. False while more slices are needed. */
        boolean searchArrival(long start){
            while(arrivalColumn<ARRIVAL_COLUMNS.length){
                if(System.nanoTime()-start>=1_500_000L)return false;
                int x=arrivalOrigin.getX()+ARRIVAL_COLUMNS[arrivalColumn][0],z=arrivalOrigin.getZ()+ARRIVAL_COLUMNS[arrivalColumn][1];
                if(arrivalY==Integer.MIN_VALUE){
                    // Only columns inside the loaded arrival footprint are read; this search never generates terrain.
                    if(!target.getChunkSource().hasChunk(x>>4,z>>4)||!target.getWorldBorder().isWithinBounds(new BlockPos(x,0,z))){arrivalColumn++;continue;}
                    arrivalY=JEDSpawnController.surfaceStart(target,x,z,maxSurfaceY);roof=target.dimensionType().hasCeiling();
                }
                boolean done=false;
                for(int blocks=0;!done&&arrivalY>=minSurfaceY&&blocks<128;blocks++,arrivalY--){
                    var candidate=new BlockPos(x,arrivalY,z);
                    boolean open=target.getBlockState(candidate).getCollisionShape(target,candidate).isEmpty();
                    if(roof){if(open)roof=false;else continue;}
                    var fluid=target.getFluidState(candidate);
                    if(!fluid.isEmpty()){if(arrivalColumn==0&&fluid.is(net.minecraft.tags.FluidTags.WATER))waterTop=candidate;done=true;}
                    // Stop at the column's surface: searching on beneath it would reach caves.
                    else if(!open)done=true;
                    else if(net.minecraft.world.level.block.Block.isFaceFull(target.getBlockState(candidate.below()).getCollisionShape(target,candidate.below()),net.minecraft.core.Direction.UP)){
                        if(safe(candidate)){position=Vec3.atBottomCenterOf(candidate);selected=true;return true;}
                        done=true;
                    }
                }
                if(!done&&arrivalY>=minSurfaceY)continue;
                arrivalColumn++;arrivalY=Integer.MIN_VALUE;
            }
            return true;
        }
        /** Clear of blocks and fluids, with support no more than one block beneath every traveler. */
        boolean standing(Vec3 feet){
            if(!clear(feet))return false;
            for(var value:members){var box=value.entity.getBoundingBox().move(feet.subtract(value.entity.position()));if(target.noCollision(value.entity,new net.minecraft.world.phys.AABB(box.minX,box.minY-1,box.minZ,box.maxX,box.minY,box.maxZ)))return false;}
            return true;
        }
        boolean clear(Vec3 feet){
            if(!target.getWorldBorder().isWithinBounds(BlockPos.containing(feet)))return false;
            for(var value:members){var box=value.entity.getBoundingBox().move(feet.subtract(value.entity.position()));if(!target.noCollision(value.entity,box)||target.containsAnyLiquid(box))return false;}
            return true;
        }
        boolean safe(BlockPos candidate){return JEDSpawnController.suitable(target,candidate,true)&&clear(Vec3.atBottomCenterOf(candidate));}
        Vec3 clamp(Vec3 value,ServerLevel level){
            if(value==null||!Double.isFinite(value.x)||!Double.isFinite(value.y)||!Double.isFinite(value.z)||Math.abs(value.y)>20_000_000)throw new IllegalStateException("Invalid teleport coordinates");
            double half=members.stream().mapToDouble(value1->value1.entity.getBbWidth()/2.0).max().orElse(.5),margin=Math.max(2,half+.01);
            var border=level.getWorldBorder();double minX=Math.max(-29_999_984,border.getMinX()+margin),maxX=Math.min(29_999_984,border.getMaxX()-margin),minZ=Math.max(-29_999_984,border.getMinZ()+margin),maxZ=Math.min(29_999_984,border.getMaxZ()-margin);
            if(minX>maxX||minZ>maxZ)throw new IllegalStateException("Destination border is too small for the traveler");
            return new Vec3(net.minecraft.util.Mth.clamp(value.x,minX,maxX),value.y,net.minecraft.util.Mth.clamp(value.z,minZ,maxZ));
        }
        CompletableFuture<Void> footprint(Vec3 value){
            int radius=Math.max(1,(int)Math.ceil(members.stream().mapToDouble(member->member.entity.getBbWidth()/2.0).max().orElse(.5)/16));
            loading=new Footprint(new ChunkPos(BlockPos.containing(value)),radius);return loading.result;
        }
        final class Footprint {
            final int minX,maxX,minZ,maxZ;int x,z,active;boolean launched;
            final CompletableFuture<Void> result=new CompletableFuture<>();
            Footprint(ChunkPos center,int radius){minX=center.x-radius;maxX=center.x+radius;minZ=center.z-radius;maxZ=center.z+radius;x=minX;z=minZ;}
            void advance(){
                int requests=0;
                while(!terminal&&!launched&&!result.isDone()&&active<4&&requests++<4){
                    var chunk=new ChunkPos(x,z);retain(target,chunk);active++;
                    CompletableFuture.supplyAsync(()->target.getChunkSource().getChunkFuture(chunk.x,chunk.z,ChunkStatus.FULL,true),Util.backgroundExecutor()).thenCompose(future->future).whenCompleteAsync((loaded,error)->{
                        active--;if(terminal)return;if(error!=null)result.completeExceptionally(error);else if(!loaded.isSuccess())result.completeExceptionally(new IllegalStateException("Destination chunk failed: "+chunk));
                        else if(launched&&active==0)result.complete(null);
                    },server);
                    if(++z>maxZ){z=minZ;if(++x>maxX)launched=true;}
                }
                if(launched&&active==0)result.complete(null);
            }
        }
        void retain(ServerLevel level,ChunkPos chunk){if(tickets.computeIfAbsent(level,ignored->new HashSet<>()).add(chunk))level.getChunkSource().addRegionTicket(TICKET,chunk,0,chunk);}
        void commit(){
            Entity vehicle=root.getVehicle();var passengers=List.copyOf(root.getPassengers());boolean detached=false;Entity arrived=null;
            var old=BYPASS.get();if(request.bypass())BYPASS.set(new Bypass(members.stream().map(Member::entity).collect(java.util.stream.Collectors.toSet()),request.dimension()));else BYPASS.remove();
            try{
                if(!request.together()){root.stopRiding();root.ejectPassengers();detached=true;}
                Vec3 speed=resetSpawnVelocity()||request.rule()!=null&&request.rule().velocity().equals("reset")?Vec3.ZERO:member(root).velocity;
                float yaw=request.yaw()==null?member(root).yaw:request.yaw(),pitch=request.pitch()==null?member(root).pitch:request.pitch();
                arrived=root.changeDimension(new DimensionTransition(target,position,speed,yaw,pitch,DimensionTransition.PLACE_PORTAL_TICKET));
                if(arrived==null)throw new IllegalStateException("Native travel was cancelled or the entity cannot enter this dimension");
                for(var value:members){var entity=value.entity instanceof ServerPlayer?value.entity:target.getEntity(value.entity.getUUID());if(entity==null||entity.level()!=target){
                        if(!value.entity.isRemoved()&&value.entity.level()==source){value.entity.setDeltaMovement(value.velocity);value.entity.fallDistance=value.fall;COOLDOWN.put(value.entity,(long)server.getTickCount()+Math.max(20,sourcePolicy.cooldown()));}
                        LogUtils.getLogger().warn("JED passenger {} did not transfer to {}; native travel was cancelled or unavailable",value.entity.getUUID(),request.dimension());
                        for(var player:members)if(player.entity instanceof ServerPlayer observer)observer.sendSystemMessage(justepicdimensions.JEDText.tr("travel.passenger"));
                        continue;
                    }
                    Vec3 motion=resetSpawnVelocity()||request.rule()!=null&&request.rule().velocity().equals("reset")?Vec3.ZERO:value.velocity;entity.setDeltaMovement(motion);entity.hurtMarked=true;
                    entity.fallDistance=fall(value,entity);COOLDOWN.put(entity,(long)server.getTickCount()+Math.max(1,sourcePolicy.cooldown()));
                    entity.setYRot(request.yaw()==null?value.yaw:request.yaw());entity.setXRot(request.pitch()==null?value.pitch:request.pitch());
                    if(entity instanceof ServerPlayer player)player.connection.send(new ClientboundSetEntityMotionPacket(player));
                }
                terminal=true;var metrics=TIMINGS.computeIfAbsent(server,ignored->new long[3]);metrics[0]++;metrics[2]=Math.max(metrics[2],System.nanoTime()-createdNanos);cleanup();result.complete(arrived);
            }catch(Throwable error){
                if(arrived==null&&detached&&!root.isRemoved()&&root.level()==source){if(vehicle!=null&&!vehicle.isRemoved()&&vehicle.level()==source)root.startRiding(vehicle,true);for(var passenger:passengers)if(!passenger.isRemoved()&&passenger.level()==source)passenger.startRiding(root,true);}
                fail(error);
            }finally{BYPASS.set(old);}
        }
        justepicdimensions.spawn.JEDSpawnDefinition.Search spawnArrival(){var definition=JEDDefinitionManager.state(server).definitions().get(request.dimension());return request.spawn()&&definition!=null?definition.spawning().spawn():null;}
        boolean resetSpawnVelocity(){var spawn=spawnArrival();return spawn!=null&&spawn.resetVelocity();}
        float fall(Member value,Entity entity){
            var spawn=spawnArrival();if(spawn!=null&&spawn.resetFallDistance())return 0;
            var rule=request.rule();if(rule==null)return value.fall;
            if(rule.removeFallDamage())return 0;
            float distance=rule.fallDistance()==null?value.fall:rule.fallDistance();
            if(rule.minimumHealth()!=null&&entity instanceof LivingEntity living)distance=Math.min(distance,(living.getHealth()-rule.minimumHealth())+3);
            return distance>=0?distance:value.fall;
        }
        void fail(Throwable error){if(terminal)return;terminal=true;for(var value:members)if(value.entity.level()==source&&!value.entity.isRemoved()&&request.automatic()){value.entity.setDeltaMovement(value.velocity);value.entity.fallDistance=value.fall;}cleanup();for(var member:members)if(member.entity instanceof ServerPlayer player)player.sendSystemMessage(justepicdimensions.JEDText.tr("connection.entry",request.dimension(),justepicdimensions.JEDText.error(error)));result.completeExceptionally(error);}
        void cleanup(){for(var value:members)JOBS.remove(value.entity,this);for(var entry:tickets.entrySet())for(var chunk:entry.getValue())entry.getKey().getChunkSource().removeRegionTicket(TICKET,chunk,0,chunk);tickets.clear();try{pin.close();}catch(Exception error){LogUtils.getLogger().error("JED travel pin cleanup failed",error);}LogUtils.getLogger().debug("JED travel {} -> {}: {} slices; maximum {} ms",source.dimension().location(),request.dimension(),slices,maxSliceNanos/1e6);}
    }
    private JEDTeleportService(){}
}
