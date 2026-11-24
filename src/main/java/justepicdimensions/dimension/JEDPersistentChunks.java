package justepicdimensions.dimension;

import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Runtime levels must receive the same persisted tickets as MinecraftServer.prepareLevels. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDPersistentChunks {
    private static final Map<ServerLevel,Restore> WORK=new LinkedHashMap<>();
    private static int cursor;
    public static void begin(ServerLevel level){var data=level.getDataStorage().get(ForcedChunksSavedData.factory(),"chunks");if(data!=null)WORK.put(level,new Restore(level,data));}
    public static CompletableFuture<Void> ready(ServerLevel level){var work=WORK.get(level);return work==null?CompletableFuture.completedFuture(null):work.result;}
    @SubscribeEvent public static void tick(ServerTickEvent.Pre event){long deadline=System.nanoTime()+2_000_000;int issued=0;var pending=WORK.values().stream().filter(work->work.level.getServer()==event.getServer()&&!work.result.isDone()).toList();if(pending.isEmpty())return;int first=Math.floorMod(cursor++,pending.size());for(int i=0;i<pending.size();i++){var work=pending.get((first+i)%pending.size());try{while(work.chunks.hasNext()&&issued<4&&System.nanoTime()<deadline){long chunk=work.chunks.nextLong();if(work.data.getChunks().contains(chunk)){work.level.getChunkSource().updateChunkForced(new ChunkPos(chunk),true);issued++;}}if(!work.chunks.hasNext()){net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.reinstatePersistentChunks(work.level,work.data);work.complete(null);}if(issued>=4||System.nanoTime()>=deadline)return;}catch(Throwable error){work.complete(error);}}}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){var work=WORK.remove(level);if(work!=null&&!work.result.isDone())work.complete(new CancellationException("Level unloaded during persistent chunk restoration"));}}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){for(var entry:List.copyOf(WORK.entrySet()))if(entry.getKey().getServer()==event.getServer()){if(!entry.getValue().result.isDone())entry.getValue().complete(new CancellationException("Server stopping"));WORK.remove(entry.getKey());}}
    private static final class Restore {
        final ServerLevel level;final ForcedChunksSavedData data;final it.unimi.dsi.fastutil.longs.LongIterator chunks;
        final CompletableFuture<Void> result=new CompletableFuture<>();final AutoCloseable pin;
        Restore(ServerLevel level,ForcedChunksSavedData data){this.level=level;this.data=data;chunks=it.unimi.dsi.fastutil.longs.LongIterators.wrap(data.getChunks().toLongArray());pin=JEDDimensionLifecycle.pinPending(level.getServer(),level.dimension().location(),level.dimension().location());}
        void complete(Throwable error){if(result.isDone())return;try{pin.close();}catch(Exception failure){if(error==null)error=failure;else error.addSuppressed(failure);}if(error==null)result.complete(null);else{if(error instanceof java.util.concurrent.CancellationException)LogUtils.getLogger().debug("JED persistent chunk restoration for {} interrupted: {}",level.dimension().location(),error.getMessage());else LogUtils.getLogger().error("JED persistent chunk restoration failed for {}",level.dimension().location(),error);result.completeExceptionally(error);}}
    }
    private JEDPersistentChunks(){}
}
