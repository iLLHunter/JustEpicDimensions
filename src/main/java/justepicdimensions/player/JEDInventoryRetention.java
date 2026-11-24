package justepicdimensions.player;

import java.util.*;
import justepicdimensions.JEDText;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

/** Moves existing native stacks inside the inventory transaction; never grants replacement items. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDInventoryRetention {
    private record Container(JEDLoadoutDefinition.Address address,List<String> path,JEDInventoryAdapter.Binding binding,boolean carried) {}
    private record Held(JEDLoadoutDefinition.Address address,int index,ItemStack item) {Held{item=item.copy();}}
    private record Slot(JEDInventoryAdapter.Binding binding,int index) {}
    public static final class Transfer {
        private final Set<ResourceLocation> ids;
        private final boolean global;
        private final List<Held> held=new ArrayList<>();
        private Transfer(Set<ResourceLocation> ids,boolean global){this.ids=ids;this.global=global;}
        private boolean matches(ItemStack item){return global&&!item.isEmpty()&&ids.contains(BuiltInRegistries.ITEM.getKey(item.getItem()));}
        public boolean empty(){return held.isEmpty();}
        boolean relevant(ServerPlayer player,CompoundTag state,ResourceLocation target){
            var containers=containers(player);
            for(var container:containers)for(int i=0;i<container.binding().size();i++)if(matches(container.binding().get(i)))return true;
            String from=state.getString("active_group"),to=JEDPlayerStateController.group(player,target);
            // A newly available source slot can recover a deferred item during group preparation.
            for(String group:List.of(from,to)){
                var saved=JEDPlayerInventoryState.groups(state).getCompound(group);
                for(var container:containers){var part=saved;for(String key:container.path())part=part.getCompound(key);
                    for(var raw:part.getList("deferred",Tag.TAG_COMPOUND))if(matches(JEDInventoryCodec.item(player,((CompoundTag)raw).getCompound("item"))))return true;
                    if(!group.equals(from))for(var raw:part.getList("items",Tag.TAG_COMPOUND))if(matches(JEDInventoryCodec.item(player,raw)))return true;
                }
            }
            return false;
        }
        /** Run before native restoration can resize/remove source accessory containers. */
        CompoundTag depart(ServerPlayer player,CompoundTag before){
            var saved=before.copy();take(player,true,saved);return saved;
        }
        public void destination(ServerPlayer player,boolean replacing){take(player,replacing,null);}
        private void take(ServerPlayer player,boolean replacing,CompoundTag snapshot){
            if(!replacing)return;
            for(var container:containers(player)){
                var binding=container.binding();
                for(int index=0;index<binding.size();index++){
                    var item=binding.get(index);if(!matches(item))continue;
                    held.add(new Held(container.address(),index,item));binding.set(index,ItemStack.EMPTY);
                    if(snapshot!=null){var part=snapshot;for(String key:container.path())part=part.getCompound(key);var items=part.getList("items",Tag.TAG_COMPOUND);if(index>=items.size())throw new IllegalStateException("Retained source slot missing from captured inventory");items.set(index,new CompoundTag());}
                }
            }
        }
        /** Placement is detached and complete before the first live write. Destination gear wins. */
        private Map<Slot,ItemStack> place(ServerPlayer player){
            var containers=containers(player);var slots=new LinkedHashMap<Slot,ItemStack>();var carried=new ArrayList<Slot>();
            for(var container:containers)for(int index=0;index<container.binding().size();index++){
                var slot=new Slot(container.binding(),index);slots.put(slot,container.binding().get(index).copy());
                if(container.carried())carried.add(slot);
            }
            for(var entry:held){
                int remaining=entry.item().getCount();
                for(var slot:carried){var current=slots.get(slot);if(!current.isEmpty()&&ItemStack.isSameItemSameComponents(current,entry.item())){int add=Math.min(remaining,Math.max(0,slot.binding().limit(slot.index(),current)-current.getCount()));current.grow(add);remaining-=add;}}
                var order=new ArrayList<Slot>();
                for(var container:containers)if(container.address().equals(entry.address())&&entry.index()<container.binding().size())order.add(new Slot(container.binding(),entry.index()));
                for(var slot:carried)if(!order.contains(slot))order.add(slot);
                for(var slot:order)if(remaining>0&&slots.get(slot).isEmpty()&&slot.binding().valid(slot.index(),entry.item())){
                    int add=Math.min(remaining,slot.binding().limit(slot.index(),entry.item()));if(add<=0)continue;
                    slots.put(slot,entry.item().copyWithCount(add));remaining-=add;
                }
                if(remaining>0)return null;
            }
            return slots;
        }
        public void restore(ServerPlayer player,ResourceLocation target){
            if(empty())return;var placed=place(player);if(placed==null)throw JEDText.stateFailure("justepicdimensions.inventory.retained_full",target);
            placed.forEach((slot,item)->{if(!ItemStack.matches(slot.binding().get(slot.index()),item))slot.binding().set(slot.index(),item);});
            JEDInventoryAdapterRegistry.reconcile(player);
        }
    }
    public static Transfer begin(ServerPlayer player,CompoundTag state,ResourceLocation target){
        boolean switched=state.contains("active_group")&&!state.getString("active_group").equals(JEDPlayerStateController.group(player,target));
        boolean global=!(switched&&JEDPlayerStateController.definition(player,target).removeGlobalInventoryItems());
        return new Transfer(JEDPlayerStateController.options(player).globalInventoryItems(),global);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void travel(EntityTravelToDimensionEvent event){
        for(var entity:event.getEntity().getSelfAndPassengers().toList())if(entity instanceof ServerPlayer player){
            try{JEDPlayerStateController.requireRetainedRoom(player,event.getDimension().location());}
            catch(RuntimeException error){event.setCanceled(true);player.sendSystemMessage(JEDText.error(error));return;}
        }
    }
    private static List<Container> containers(ServerPlayer player){
        var result=new ArrayList<Container>();var seen=new IdentityHashMap<Object,Set<String>>();
        for(var adapter:JEDInventoryAdapterRegistry.adapters())for(var address:adapter.retainedContainers(player)){
            var binding=adapter.resolveRetained(player,address);if(binding==null)continue;
            if(seen.computeIfAbsent(binding.owner(),key->new HashSet<>()).add(binding.part()))result.add(new Container(address,adapter.snapshotPath(address),binding,adapter.carriedContainer(address)));
        }
        return result;
    }
    private JEDInventoryRetention(){}
}
