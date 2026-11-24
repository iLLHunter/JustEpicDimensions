package justepicdimensions.player;

import java.lang.reflect.*;
import java.util.*;
import com.mojang.logging.LogUtils;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.items.IItemHandler;

/** Creation receipts are tied to this death, not an arbitrary older recovery-history record. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDGraveRegistry {
    public enum Recovery { NONE, RECOVERABLE, UNKNOWN }
    public static final Set<String> BACKENDS=Set.of("gravestone","corpse","tombstone","simpletomb","yigd");
    private record Key(MinecraftServer server,UUID player) {}
    private static final class Death {
        final ServerPlayer player;final CompoundTag value=new CompoundTag();final ListTag refs=new ListTag();final Map<String,Object> objects=new HashMap<>();final Set<String> finished=new HashSet<>();
        boolean collecting=true;
        Death(ServerPlayer player){this.player=player;value.putString("token",UUID.randomUUID().toString());value.putString("group",JEDPlayerStateController.group(player));value.putBoolean("keep_inventory",player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY));value.putBoolean("known_empty",player.getInventory().isEmpty()&&player.getEnderChestInventory().isEmpty()&&!hasItems(JEDInventoryAdapterRegistry.capture(player,new CompoundTag())));}
    }
    private static final Map<Key,Death> DEATHS=new HashMap<>();
    public static void begin(ServerPlayer player){DEATHS.put(new Key(player.server,player.getUUID()),new Death(player));}
    public static void dropsDone(ServerPlayer player){var death=DEATHS.get(new Key(player.server,player.getUUID()));if(death!=null&&death.collecting)death.value.putBoolean("native_drops_completed",true);}
    private static boolean hasItems(CompoundTag value){
        for(String key:value.getAllKeys()){
            var tag=value.get(key);
            if(key.equals("items")&&tag instanceof ListTag list)for(var entry:list)if(entry instanceof CompoundTag stack&&!stack.isEmpty())return true;
            if(tag instanceof CompoundTag child&&hasItems(child))return true;
        }return false;
    }
    public static void finish(ServerPlayer player,boolean committed){
        var key=new Key(player.server,player.getUUID());var death=DEATHS.get(key);if(death==null)return;
        if(!committed){DEATHS.remove(key);return;}death.collecting=false;persist(death);
    }
    private static void persist(Death death){
        var value=death.value.copy();value.put("references",death.refs.copy());var done=new CompoundTag();death.finished.forEach(id->done.putBoolean(id,true));value.put("finished",done);
        var state=JEDPlayerInventoryState.read(death.player);state.put("death",value);JEDPlayerInventoryState.commit(death.player,state);
    }
    public static void handlerFinished(Object event,String backend){
        try{Object entity;try{entity=invoke(event,"getPlayer");}catch(ReflectiveOperationException absent){entity=invoke(event,"getEntity");}
            if(entity instanceof ServerPlayer player){var death=DEATHS.get(new Key(player.server,player.getUUID()));if(death!=null&&death.collecting)death.finished.add(backend);}
        }catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED could not confirm {} death-handler completion",backend,error);}
    }
    public static void blockCreated(Object object,Object possiblePlayer,String backend){
        if(!(object instanceof BlockEntity block)||!(block.getLevel() instanceof ServerLevel level))return;
        try{
            ServerPlayer player=possiblePlayer instanceof ServerPlayer p?p:null;
            String nativeId="";
            if(backend.equals("gravestone")){var death=invoke(block,"getDeath");if(death==null)return;player=find(level.getServer(),(UUID)invoke(death,"getPlayerUUID"));nativeId=invoke(death,"getId").toString();}
            if(backend.equals("tombstone"))nativeId=String.valueOf(invoke(block,"getOwnerDeathTime"));
            if(player==null)return;
            var current=DEATHS.get(new Key(player.server,player.getUUID()));if(current==null||!current.collecting)return;
            var ref=reference(backend,level,block.getBlockPos());ref.putString("native_id",nativeId);current.refs.add(ref);current.objects.put(identity(ref),block);
        }catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED could not identify newly created {} grave",backend,error);}
    }
    public static void yigdCreated(Object component){
        try{
            var owner=invoke(component,"getOwner");var profile=(com.mojang.authlib.GameProfile)invoke(owner,"gameProfile");
            var level=(ServerLevel)invoke(component,"getWorld");if(level==null)return;var player=find(level.getServer(),profile.getId());if(player==null)return;
            var current=DEATHS.get(new Key(player.server,player.getUUID()));if(current==null||!current.collecting)return;
            var ref=reference("yigd",level,(BlockPos)invoke(component,"getPos"));ref.putString("native_id",invoke(component,"getGraveId").toString());current.refs.add(ref);current.objects.put(identity(ref),component);
        }catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED could not associate a YIGD grave with its death",error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void entityCreated(EntityJoinLevelEvent event){
        var entity=event.getEntity();if(!(event.getLevel() instanceof ServerLevel level)||!entity.getClass().getName().equals("de.maxhenkel.corpse.entities.CorpseEntity"))return;
        try{
            var nativeDeath=invoke(entity,"getDeath");if(nativeDeath==null)return;var owner=(UUID)invoke(nativeDeath,"getPlayerUUID");
            var current=DEATHS.get(new Key(level.getServer(),owner));if(current==null||!current.collecting)return;
            var ref=reference("corpse",level,entity.blockPosition());ref.putUUID("entity",entity.getUUID());ref.putString("native_id",invoke(nativeDeath,"getId").toString());current.refs.add(ref);current.objects.put(identity(ref),entity);
        }catch(ReflectiveOperationException error){LogUtils.getLogger().warn("JED could not identify newly created corpse",error);}
    }
    public static Recovery recovery(ServerPlayer player,CompoundTag death){
        if(!death.contains("token"))return Recovery.UNKNOWN;
        var context=DEATHS.get(new Key(player.server,player.getUUID()));
        if(context!=null&&!context.value.getString("token").equals(death.getString("token")))context=null;
        boolean unknown=false;
        for(String backend:BACKENDS)if(ModList.get().isLoaded(backend)){
            boolean observed=false;
            for(var raw:death.getList("references",Tag.TAG_COMPOUND)){
                var ref=(CompoundTag)raw;if(!ref.getString("backend").equals(backend))continue;observed=true;
                Recovery result;
                try{result=inspect(player,ref,context==null?null:context.objects.get(identity(ref)));}
                catch(ReflectiveOperationException|RuntimeException error){LogUtils.getLogger().warn("JED {} recovery check failed for death {}",backend,death.getString("token"),error);result=Recovery.UNKNOWN;}
                if(result==Recovery.RECOVERABLE)return result;if(result==Recovery.UNKNOWN)unknown=true;
            }
            if(!observed&&!death.getCompound("finished").getBoolean(backend)){
                boolean noItems=death.getBoolean("native_drops_completed")&&(death.getBoolean("known_empty")||death.getBoolean("keep_inventory"));
                if(!noItems||!handlerVerified(backend))unknown=true;
            }
        }
        return unknown?Recovery.UNKNOWN:Recovery.NONE;
    }
    private static boolean handlerVerified(String backend){
        String name=switch(backend){case "gravestone"->"de.maxhenkel.gravestone.events.DeathEvents";case "corpse"->"de.maxhenkel.corpse.events.DeathEvents";case "tombstone"->"ovh.corail.tombstone.event.EventHandler";case "simpletomb"->"com.lothrazar.simpletomb.event.PlayerTombEvents";case "yigd"->"com.b1n_ry.yigd.events.ServerEventHandler";default->"";};
        return "verified".equals(System.getProperty("jed.grave.coremod."+name));
    }
    private static Recovery inspect(ServerPlayer player,CompoundTag ref,Object object)throws ReflectiveOperationException{
        String backend=ref.getString("backend");
        var level=player.server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(ref.getString("dimension"))));
        if(level==null)return Recovery.UNKNOWN;
        if(backend.equals("yigd")){
            if(object==null){var manager=Class.forName("com.b1n_ry.yigd.data.DeathInfoManager").getField("INSTANCE").get(null);object=((Optional<?>)invoke(manager,"getGrave",new Class<?>[]{UUID.class},UUID.fromString(ref.getString("native_id")))).orElse(null);}
            if(object==null)return Recovery.NONE;
            if(!invoke(object,"getStatus").toString().equals("UNCLAIMED"))return Recovery.NONE;
            if((boolean)invoke(object,"isGraveEmpty"))return Recovery.NONE;
            level=(ServerLevel)invoke(object,"getWorld");var pos=(BlockPos)invoke(object,"getPos");
            if(level==null||!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ())))return Recovery.UNKNOWN;
            var block=level.getBlockEntity(pos);if(block==null)return Recovery.UNKNOWN;
            if(!block.getClass().getName().equals("com.b1n_ry.yigd.block.entity.GraveBlockEntity"))return Recovery.UNKNOWN;
            return Objects.equals(invoke(block,"getGraveId"),UUID.fromString(ref.getString("native_id")))?Recovery.RECOVERABLE:Recovery.UNKNOWN;
        }
        if(backend.equals("corpse")){
            Entity corpse=level.getEntity(ref.getUUID("entity"));if(corpse==null)return level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.core.BlockPos.getX(ref.getLong("position"))),net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.core.BlockPos.getZ(ref.getLong("position"))))?Recovery.NONE:Recovery.UNKNOWN;
            if(!corpse.getClass().getName().equals("de.maxhenkel.corpse.entities.CorpseEntity"))return Recovery.NONE;
            var nativeDeath=invoke(corpse,"getDeath");if(nativeDeath==null||!invoke(nativeDeath,"getId").toString().equals(ref.getString("native_id")))return Recovery.NONE;
            return (boolean)invoke(corpse,"isEmpty")?Recovery.NONE:Recovery.RECOVERABLE;
        }
        var pos=BlockPos.of(ref.getLong("position"));if(!level.hasChunk(net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()),net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ())))return Recovery.UNKNOWN;
        var block=level.getBlockEntity(pos);if(block==null)return Recovery.NONE;
        if(backend.equals("gravestone")){
            if(!block.getClass().getName().equals("de.maxhenkel.gravestone.tileentity.GraveStoneTileEntity"))return Recovery.NONE;
            var nativeDeath=invoke(block,"getDeath");if(nativeDeath==null||!invoke(nativeDeath,"getId").toString().equals(ref.getString("native_id")))return Recovery.NONE;
            for(Object stack:(Iterable<?>)invoke(nativeDeath,"getAllItems"))if(stack instanceof ItemStack item&&!item.isEmpty())return Recovery.RECOVERABLE;
            return Recovery.NONE;
        }
        if(backend.equals("tombstone")){
            if(!block.getClass().getName().equals("ovh.corail.tombstone.block.entity.BlockEntityPlayerGrave"))return Recovery.NONE;
            if(!player.getUUID().equals(invoke(block,"getOwnerId"))||!String.valueOf(invoke(block,"getOwnerDeathTime")).equals(ref.getString("native_id")))return Recovery.NONE;
            return contents((IItemHandler)invoke(block,"getInventory"));
        }
        if(backend.equals("simpletomb")){
            if(!block.getClass().getName().equals("com.lothrazar.simpletomb.block.BlockEntityTomb"))return Recovery.NONE;
            if(!(boolean)invoke(block,"isOwner",new Class<?>[]{Player.class},player))return Recovery.NONE;
            return contents((IItemHandler)invoke(block,"getHandler",new Class<?>[]{Direction.class},(Object)null));
        }
        return Recovery.UNKNOWN;
    }
    private static Recovery contents(IItemHandler handler){if(handler==null)return Recovery.UNKNOWN;for(int i=0;i<handler.getSlots();i++)if(!handler.getStackInSlot(i).isEmpty())return Recovery.RECOVERABLE;return Recovery.NONE;}
    private static ServerPlayer find(MinecraftServer server,UUID id){var context=DEATHS.get(new Key(server,id));return context==null?null:context.player;}
    private static CompoundTag reference(String backend,ServerLevel level,BlockPos pos){var tag=new CompoundTag();tag.putString("backend",backend);tag.putString("dimension",level.dimension().location().toString());tag.putLong("position",pos.asLong());return tag;}
    private static String identity(CompoundTag ref){return ref.getString("backend")+":"+ref.getString("native_id")+":"+ref.getLong("position");}
    private static Object invoke(Object object,String name)throws ReflectiveOperationException{return invoke(object,name,new Class<?>[0]);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws ReflectiveOperationException{return object.getClass().getMethod(name,types).invoke(object,args);}
    public static void consumed(ServerPlayer player){DEATHS.remove(new Key(player.server,player.getUUID()));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){DEATHS.keySet().removeIf(key->key.server()==event.getServer());}
    private JEDGraveRegistry(){}
}
