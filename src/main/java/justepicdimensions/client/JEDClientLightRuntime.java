package justepicdimensions.client;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.lighting.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.event.level.*;

@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDClientLightRuntime {
    private static ClientLevel level;
    private static JEDClientLightTracker tracker;
    private static Map<ResourceLocation,JEDClientLightPolicy> policies=Map.of();
    private static long revision=-1,ticks;
    static {
        JEDClientLightHooks.packetAdapter(JEDClientLightRuntime::packet);
        JEDLightPolicySync.clientReceiver(JEDClientLightRuntime::receive);
    }
    private static void receive(JEDLightPolicySync.Snapshot snapshot){
        if(snapshot.revision()<=revision)return;
        revision=snapshot.revision();policies=snapshot.policies();
        JEDBrightnessTables.CLIENT.apply(snapshot);
        if(tracker!=null)tracker.policy(policies.get(level.dimension().location()));
    }
    private static JEDClientLightTracker ensure(ClientLevel next){
        if(next==level)return tracker;
        if(!"verified".equals(System.getProperty("jed.light.coremod.packets")))
            throw justepicdimensions.JustEpicDimensions.arbitrageMissing("client light packet","client packet handling");
        retire();level=next;
        tracker=new JEDClientLightTracker(new JEDClientLightView(){
            public Object lifetime(){return next;}
            public Object source(){return next.getChunkSource();}
            public BlockGetter geometry(){return next;}
            public LevelLightEngine engine(){return next.getLightEngine();}
            public LevelChunk chunk(int x,int z){return next.getChunkSource().getChunk(x,z,false);}
            public int minSection(){return next.getMinSection();}
            public int sectionCount(){return next.getSectionsCount();}
            public int playerSection(){var p=Minecraft.getInstance().player;return p==null?0:p.blockPosition().getY()>>4;}
            public int playerChunkX(){var p=Minecraft.getInstance().player;return p==null?0:p.chunkPosition().x;}
            public int playerChunkZ(){var p=Minecraft.getInstance().player;return p==null?0:p.chunkPosition().z;}
        },JEDLightingConfig.budget());
        tracker.tick(ticks);tracker.policy(policies.get(next.dimension().location()));JEDClientLightHooks.register(tracker);return tracker;
    }
    private static JEDClientLightTracker.Receipt packet(Object nativeLevel,Object nativePacket){
        if(!(nativeLevel instanceof ClientLevel l))return null;
        var t=ensure(l);
        if(nativePacket instanceof ClientboundLevelChunkWithLightPacket p)return t.receipt(p.getX(),p.getZ(),true,sky(p.getLightData()),block(p.getLightData()));
        if(nativePacket instanceof ClientboundLightUpdatePacket p)return t.receipt(p.getX(),p.getZ(),false,sky(p.getLightData()),block(p.getLightData()));
        return null;
    }
    private static BitSet sky(ClientboundLightUpdatePacketData data){var mask=(BitSet)data.getSkyYMask().clone();mask.or(data.getEmptySkyYMask());return mask;}
    private static BitSet block(ClientboundLightUpdatePacketData data){var mask=(BitSet)data.getBlockYMask().clone();mask.or(data.getEmptyBlockYMask());return mask;}
    @SubscribeEvent public static void tick(ClientTickEvent.Pre event){
        ticks++;var current=Minecraft.getInstance().level;
        if(current!=null)ensure(current).tick(ticks);else retire();
    }
    @SubscribeEvent public static void loaded(ChunkEvent.Load event){
        if(event.getLevel() instanceof ClientLevel l&&(l==level||l==Minecraft.getInstance().level)){
            var p=event.getChunk().getPos();ensure(l).loaded(p.x,p.z);
        }
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void loaded(LevelEvent.Load event){
        if(event.getLevel() instanceof ClientLevel l)ensure(l);
    }
    @SubscribeEvent public static void unloaded(ChunkEvent.Unload event){
        if(event.getLevel()==level&&tracker!=null){var p=event.getChunk().getPos();tracker.unload(p.x,p.z);}
    }
    @SubscribeEvent public static void unloaded(LevelEvent.Unload event){if(event.getLevel()==level)retire();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){retire();policies=Map.of();revision=-1;JEDBrightnessTables.CLIENT.clear();}
    private static void retire(){if(tracker!=null)JEDClientLightHooks.retire(tracker);tracker=null;level=null;}
    public static JEDClientLightTracker tracker(){return tracker;}
    public static void configChanged(){if(tracker!=null)tracker.budget(JEDLightingConfig.budget());}
    /** Read-only connection diagnostics; callers run on the client thread. */
    public static Map<ResourceLocation,JEDClientLightPolicy> policies(){return policies;}
    public static long policyRevision(){return revision;}
    private JEDClientLightRuntime(){}
}
