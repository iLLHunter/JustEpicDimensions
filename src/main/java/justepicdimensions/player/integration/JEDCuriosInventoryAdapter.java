package justepicdimensions.player.integration;

import java.util.*;
import justepicdimensions.player.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import top.theillusivec4.curios.api.*;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.inventory.*;
import top.theillusivec4.curios.common.event.CuriosEventHandler;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios;

/** Native handlers preserve render/active states and permanent slot modifiers. */
public final class JEDCuriosInventoryAdapter implements JEDInventoryAdapter {
    private static final ClassValue<Optional<java.lang.reflect.Method>> OWNERS=new ClassValue<>(){
        protected Optional<java.lang.reflect.Method> computeValue(Class<?> type){
            try{var method=type.getMethod("container");var contract=Class.forName("io.wispforest.accessories.api.AccessoriesContainer",false,type.getClassLoader());return contract.isAssignableFrom(method.getReturnType())?Optional.of(method):Optional.empty();}
            catch(NoSuchMethodException|ClassNotFoundException absent){return Optional.empty();}
        }
    };
    public String id(){return "curios";}
    public List<JEDLoadoutDefinition.Address> retainedContainers(ServerPlayer player){var result=new ArrayList<JEDLoadoutDefinition.Address>();all(player).forEach((name,handler)->{if(!facade(handler))for(boolean cosmetic:List.of(false,true))result.add(new JEDLoadoutDefinition.Address(id(),name,cosmetic));});return List.copyOf(result);}
    public List<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment> equipped(ServerPlayer player) {
        var result = new ArrayList<justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment>();
        all(player).forEach((name, handler) -> {
            if (facade(handler)) return;
            var stacks = handler.getStacks();
            for (int index = 0; index < stacks.getSlots(); index++) {
                if (index < handler.getActiveStates().size() && !handler.getActiveStates().get(index)) continue;
                var item = stacks.getStackInSlot(index);
                if (!item.isEmpty()) result.add(new justepicdimensions.api.player.JEDEquipmentEffectProviders.Equipment(id(), name, index, item));
            }
        });
        return List.copyOf(result);
    }
    private Map<String,ICurioStacksHandler> all(ServerPlayer player){return CuriosApi.getCuriosInventory(player).map(h->h.getCurios()).orElse(Map.of());}
    private static Object owner(ICurioStacksHandler handler){
        var accessor=OWNERS.get(handler.getClass());
        if(accessor.isPresent()){
            try{return Objects.requireNonNull(accessor.get().invoke(handler),"Missing Curios facade backing inventory");}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot identify Curios facade backing inventory",error);}
        }return handler;
    }
    private static boolean facade(ICurioStacksHandler handler){return owner(handler)!=handler;}
    public Binding resolve(ServerPlayer player,JEDLoadoutDefinition.Address address){
        var handler=all(player).get(address.container());if(handler==null)return null;
        var stacks=address.cosmetic()?handler.getCosmeticStacks():handler.getStacks();
        return new Binding(){
            public Object owner(){return JEDCuriosInventoryAdapter.owner(handler);}
            public String part(){return address.cosmetic()?"cosmetic":"functional";}
            public int size(){return stacks.getSlots();}
            public ItemStack get(int index){return stacks.getStackInSlot(index);}
            public void set(int index,ItemStack value){stacks.setStackInSlot(index,value.copy());}
            public boolean valid(int index,ItemStack value){return value.isEmpty()||stacks.isItemValid(index,value);}
            public int limit(int index,ItemStack value){return Math.min(stacks.getSlotLimit(index),value.getMaxStackSize());}
        };
    }
    private Binding binding(ServerPlayer p,String name,boolean cosmetic){return resolve(p,new JEDLoadoutDefinition.Address(id(),name,cosmetic));}
    public CompoundTag capture(ServerPlayer player,CompoundTag previous){
        var result=previous.copy();
        all(player).forEach((name,handler)->{if(facade(handler))return;var entry=previous.getCompound(name).copy();
            entry.put("functional",JEDInventoryCodec.capture(player,binding(player,name,false),entry.getCompound("functional")));
            entry.put("cosmetic",JEDInventoryCodec.capture(player,binding(player,name,true),entry.getCompound("cosmetic")));
            var metadata=handler.serializeNBT();metadata.remove("Stacks");metadata.remove("Cosmetics");entry.put("metadata",metadata);result.put(name,entry);
        });var names=new TreeSet<String>();all(player).forEach((name,handler)->{if(!facade(handler))names.add(name);});JEDInventoryCodec.nativeContainers(result,previous,names,false);return result;
    }
    public void validate(ServerPlayer player,CompoundTag saved){all(player).forEach((name,handler)->{if(facade(handler))return;var entry=saved.getCompound(name);JEDInventoryCodec.validate(player,entry.getCompound("functional"),Integer.MAX_VALUE);JEDInventoryCodec.validate(player,entry.getCompound("cosmetic"),Integer.MAX_VALUE);});}
    public CompoundTag restore(ServerPlayer player,CompoundTag saved){
        return restoreSelected(player,saved,all(player).keySet());
    }
    private CompoundTag restoreSelected(ServerPlayer player,CompoundTag saved,Set<String> selected){
        validate(player,saved);var handlers=new LinkedHashMap<>(all(player));handlers.keySet().retainAll(selected);var result=saved.copy();
        handlers.forEach((name,handler)->{if(facade(handler))return;for(boolean cosmetic:List.of(false,true)){var b=binding(player,name,cosmetic);for(int i=0;i<b.size();i++)b.set(i,ItemStack.EMPTY);}});
        reconcile(player);
        handlers.forEach((name,handler)->{if(facade(handler))return;handler.clearModifiers();var entry=saved.getCompound(name).copy();
            var metadata=entry.getCompound("metadata").copy();metadata.remove("SavedBaseSize");if(!metadata.isEmpty())handler.deserializeNBT(metadata);
            else {Collections.fill(handler.getRenders(),true);Collections.fill(handler.getActiveStates(),true);}
            // Native deserialization reads state lists before permanent slot modifiers resize them.
            // Apply their saved indices again after sizing, then reconcile active flags while empty.
            for(var raw:metadata.getCompound("Renders").getList("Renders",Tag.TAG_COMPOUND)){var flag=(CompoundTag)raw;int index=flag.getInt("Slot");if(index>=0&&index<handler.getRenders().size())handler.getRenders().set(index,flag.getBoolean("Render"));}
            for(var raw:metadata.getCompound("ActiveStates").getList("ActiveStates",Tag.TAG_COMPOUND)){var flag=(CompoundTag)raw;int index=flag.getInt("Slot");if(index>=0&&index<handler.getActiveStates().size())handler.getActiveStates().set(index,flag.getBoolean("ActiveState"));}
            for(int index=0;index<handler.getSlots();index++)handler.updateActiveState(index);
            entry.put("functional",JEDInventoryCodec.restore(player,binding(player,name,false),entry.getCompound("functional")));
            entry.put("cosmetic",JEDInventoryCodec.restore(player,binding(player,name,true),entry.getCompound("cosmetic")));result.put(name,entry);
        });var names=new TreeSet<String>();all(player).forEach((name,handler)->{if(!facade(handler))names.add(name);});JEDInventoryCodec.nativeContainers(result,saved,names,true);return result;
    }
    public CompoundTag recover(ServerPlayer player,CompoundTag saved,boolean returned){
        var present=saved.getList("@available_containers",Tag.TAG_STRING);var restore=new HashSet<String>();
        all(player).forEach((name,handler)->{if(!facade(handler)&&saved.contains(name)&&(returned||saved.contains("@available_containers")&&!present.contains(StringTag.valueOf(name)))){
            boolean empty=true;for(boolean cosmetic:List.of(false,true)){var b=binding(player,name,cosmetic);for(int i=0;i<b.size();i++)empty&=b.get(i).isEmpty();}if(empty)restore.add(name);
        }});
        var result=restore.isEmpty()?saved.copy():restoreSelected(player,saved,restore);reconcile(player);
        all(player).forEach((name,handler)->{if(facade(handler))return;var entry=result.getCompound(name).copy();boolean missing=!restore.contains(name)&&(returned||saved.contains("@available_containers")&&!present.contains(StringTag.valueOf(name)));
            for(boolean cosmetic:List.of(false,true)){String part=cosmetic?"cosmetic":"functional";entry.put(part,JEDInventoryCodec.recover(player,binding(player,name,cosmetic),entry.getCompound(part),missing));}result.put(name,entry);
        });return capture(player,result);
    }
    public void reconcile(ServerPlayer player){
        if(all(player).values().stream().allMatch(JEDCuriosInventoryAdapter::facade))return;
        if(!"verified".equals(System.getProperty("jed.player.coremod.curios"))||!"verified".equals(System.getProperty("jed.player.coremod.curios_sizing")))throw new IllegalStateException("Curios equipment-reconciliation bridge is unavailable");
        JEDAccessoryRuntime.reconcile(()->{
            var events=new CuriosEventHandler();events.tick(new EntityTickEvent.Post(player));
            // Cached group metadata may contain vanilla equipment's transient slot modifiers.
            // Curios clears that cache while processing accessory grantors. Re-establish only
            // the actual equipped vanilla modifiers through Curios' native handler before resize.
            for(var slot:net.minecraft.world.entity.EquipmentSlot.values())if(!player.getItemBySlot(slot).isEmpty())events.livingEquipmentChange(new net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent(player,slot,ItemStack.EMPTY,player.getItemBySlot(slot)));
            // Curios defers resizing while it reconciles grantor equipment. Finish only after
            // that pass, so another group change in this same tick sees the actual capacity.
            JEDAccessoryRuntime.finishSizing(()->all(player).values().forEach(h->{if(!facade(h))h.update();}));
            CuriosApi.getCuriosInventory(player).ifPresent(h->h.handleInvalidStacks());
        });
    }
    public void sync(ServerPlayer player){if(all(player).values().stream().anyMatch(h->!facade(h)))PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,new SPacketSyncCurios(player.getId(),all(player)));}
    public static void tick(ICurio curio,SlotContext context){if(!JEDAccessoryRuntime.reconciling())curio.curioTick(context);}
    public static int sizingCacheTick(int original,top.theillusivec4.curios.api.type.capability.ICuriosItemHandler handler){return JEDAccessoryRuntime.finishingSizing()?handler.getWearer().tickCount-1:original;}
}
