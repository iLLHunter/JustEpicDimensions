package justepicdimensions.lighting;

import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/** Common-safe bridge. Merely transforming a server engine never loads Minecraft client classes. */
public final class JEDClientLightHooks {
    private JEDClientLightHooks() {}
    private static final Map<Object,JEDClientLightTracker> ENGINES=Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Object,JEDClientLightTracker> STORAGES=Collections.synchronizedMap(new IdentityHashMap<>());
    private record Scope(JEDClientLightTracker.Receipt receipt,Scope previous) {}
    private static final ThreadLocal<Scope> ORIGIN=new ThreadLocal<>();
    public interface PacketAdapter { JEDClientLightTracker.Receipt begin(Object level,Object packet); }
    private static volatile PacketAdapter packetAdapter;
    public static void packetAdapter(PacketAdapter adapter){packetAdapter=adapter;}
    public static void register(JEDClientLightTracker tracker){
        ENGINES.put(tracker.view.engine(),tracker);
    }
    public static void retire(JEDClientLightTracker tracker){
        ENGINES.remove(tracker.view.engine(),tracker);
        if(tracker.blockStorage!=null)STORAGES.remove(tracker.blockStorage,tracker);
        if(tracker.skyStorage!=null)STORAGES.remove(tracker.skyStorage,tracker);
        tracker.blockStorage=tracker.skyStorage=null;tracker.close();
        var scope=ORIGIN.get();if(scope!=null&&scope.receipt!=null&&scope.receipt.tracker==tracker)ORIGIN.remove();
    }
    public static void packetBegin(Object level,Object packet){
        var adapter=packetAdapter;
        var receipt=adapter==null?null:adapter.begin(level,packet);
        ORIGIN.set(new Scope(receipt,ORIGIN.get()));
    }
    private static Scope pop(){var scope=ORIGIN.get();if(scope==null||scope.previous==null)ORIGIN.remove();else ORIGIN.set(scope.previous);return scope;}
    public static void packetEnd(){var scope=pop();if(scope!=null&&scope.receipt!=null)scope.receipt.tracker.geometryApplied(scope.receipt);}
    public static void packetFailed(){pop();}
    public static Runnable wrapTask(Runnable nativeTask){
        var scope=ORIGIN.get();var receipt=scope==null?null:scope.receipt;if(receipt==null)return nativeTask;
        return ()->{
            var previous=ORIGIN.get();ORIGIN.set(new Scope(receipt,previous));
            try{nativeTask.run();receipt.tracker.applied(receipt);}
            finally{if(previous==null)ORIGIN.remove();else ORIGIN.set(previous);}
        };
    }
    private static JEDClientLightTracker current(Map<Object,JEDClientLightTracker> map,Object key){
        var tracker=map.get(key);return tracker!=null&&!tracker.closed&&tracker.thread==Thread.currentThread()?tracker:null;
    }
    public static void queued(Object engine,LightLayer layer,SectionPos section,DataLayer data){
        var t=current(ENGINES,engine);
        if(t!=null&&t.view.engine().getLayerListener(layer)!=net.minecraft.world.level.lighting.LayerLightEventListener.DummyLightLayerEventListener.INSTANCE)
            t.queued(layer,section,data,ORIGIN.get()==null?null:ORIGIN.get().receipt);
    }
    public static void consumed(Object storage,Object source,LightLayer layer,long section,DataLayer data){
        var t=current(STORAGES,storage);if(t!=null&&t.view.source()==source)t.consumed(storage,layer,section,data);
    }
    public static void published(Object storage,Object source,LightLayer layer){var t=current(STORAGES,storage);if(t!=null&&t.view.source()==source)t.published(storage,layer);}
    public static void beforeDrain(Object engine,Object block,Object sky){
        var t=current(ENGINES,engine);if(t==null)return;
        if(t.blockStorage!=block){if(t.blockStorage!=null)STORAGES.remove(t.blockStorage,t);t.blockStorage=block;if(block!=null)STORAGES.put(block,t);}
        if(t.skyStorage!=sky){if(t.skyStorage!=null)STORAGES.remove(t.skyStorage,t);t.skyStorage=sky;if(sky!=null)STORAGES.put(sky,t);}
        t.scheduler.beforeDrain();
    }
    public static int afterDrain(int nativeResult,Object engine){var t=current(ENGINES,engine);if(t!=null)t.scheduler.afterDrain(nativeResult);return nativeResult;}
}
