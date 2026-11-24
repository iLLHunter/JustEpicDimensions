package justepicdimensions.player;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Preflight the complete kit before clearing or replacing any selected container. */
public final class JEDLoadoutController {
    private record Key(Object owner,String part) {}
    public record Change(JEDInventoryAdapter.Binding binding,int index,ItemStack value) {}
    public record Skipped(ItemStack item,String slot,JEDLoadoutDefinition.Address address,int index,boolean missing) {public Skipped {item=item.copy();}}
    public record Prepared(List<Change> changes,List<Skipped> skipped) {public Prepared {changes=List.copyOf(changes);skipped=List.copyOf(skipped);}}
    public static Prepared prepare(ServerPlayer player,JEDLoadoutDefinition kit,JEDPlayerDefinition.Mode mode) {
        return prepare(player,kit,mode,new HashSet<>(),new HashMap<>());
    }
    private static Prepared prepare(ServerPlayer player,JEDLoadoutDefinition kit,JEDPlayerDefinition.Mode mode,Set<Key> cleared,Map<Key,Set<Integer>> assigned) {
        var changes=new ArrayList<Change>();var skipped=new ArrayList<Skipped>();var physical=new HashSet<Key>();
        for(var container:kit.containers()){
            var binding=JEDInventoryAdapterRegistry.resolve(player,container.address());
            if(binding==null){for(var entry:container.slots())skipped.add(missing(entry,container.address(),true));continue;}
            var key=new Key(binding.owner(),binding.part());
            if(!physical.add(key))throw new IllegalStateException(kit.id()+": the same backing inventory is addressed twice: "+container.address());
            var target=new TreeMap<Integer,ItemStack>();
            var reserved=assigned.computeIfAbsent(key,k->new HashSet<>());
            for(var entry:container.slots()){
                ItemStack desired=entry.item();int selected=entry.index();
                boolean search=!container.address().backend().equals("minecraft")&&(mode==JEDPlayerDefinition.Mode.FILL_EMPTY||mode==JEDPlayerDefinition.Mode.TOP_UP)&&!desired.isEmpty();
                if(search)selected=available(binding,desired,selected,mode,reserved);
                if(selected<0||selected>=binding.size()){skipped.add(missing(entry,container.address(),!search||binding.size()==0));continue;}
                reserved.add(selected);ItemStack current=binding.get(selected);
                boolean changesSlot=switch(mode){
                    case FILL_EMPTY->current.isEmpty();
                    case TOP_UP->current.isEmpty()||ItemStack.isSameItemSameComponents(current,desired)&&current.getCount()<desired.getCount();
                    default->true;
                };
                if(changesSlot){
                    if(!desired.isEmpty()&&(desired.getCount()>binding.limit(selected,desired)||!binding.valid(selected,desired)))
                        throw new IllegalStateException(kit.id()+": invalid equipment or stack size for "+container.address()+"["+selected+"]");
                    target.put(selected,desired);
                }
            }
            if(mode==JEDPlayerDefinition.Mode.REPLACE_CONTAINERS&&cleared.add(key))for(int index=0;index<binding.size();index++)target.putIfAbsent(index,ItemStack.EMPTY);
            target.forEach((index,value)->changes.add(new Change(binding,index,value.copy())));
        }
        return new Prepared(changes,skipped);
    }
    public static void apply(List<Change> changes){for(var change:changes)change.binding().set(change.index(),change.value());}
    /** Equip available entries, reconcile native slot growth, then retry only previously unavailable entries. */
    public static List<Skipped> execute(ServerPlayer player,JEDLoadoutDefinition kit,JEDPlayerDefinition.Mode mode){
        var cleared=new HashSet<Key>();var assigned=new HashMap<Key,Set<Integer>>();var pending=kit;int entries=kit.containers().stream().mapToInt(c->c.slots().size()).sum();
        for(int pass=0;pass<=entries;pass++){
            var plan=prepare(player,pending,mode,cleared,assigned);apply(plan.changes());JEDInventoryAdapterRegistry.reconcile(player);
            if(plan.skipped().isEmpty()||plan.changes().isEmpty()||pass==entries)return plan.skipped();
            var containers=new LinkedHashMap<JEDLoadoutDefinition.Address,List<JEDLoadoutDefinition.Entry>>();
            for(var item:plan.skipped())containers.computeIfAbsent(item.address(),k->new ArrayList<>()).add(new JEDLoadoutDefinition.Entry(item.index(),item.item()));
            pending=new JEDLoadoutDefinition(kit.id(),containers.entrySet().stream().map(e->new JEDLoadoutDefinition.Container(e.getKey(),e.getValue())).toList());
        }throw new AssertionError("Unreachable loadout retry bound");
    }
    private static int available(JEDInventoryAdapter.Binding binding,ItemStack desired,int preferred,JEDPlayerDefinition.Mode mode,Set<Integer> reserved){
        var order=new ArrayList<Integer>();if(preferred<binding.size())order.add(preferred);for(int i=0;i<binding.size();i++)if(i!=preferred)order.add(i);
        if(mode==JEDPlayerDefinition.Mode.TOP_UP)for(int index:order)if(!reserved.contains(index)&&ItemStack.isSameItemSameComponents(binding.get(index),desired))return index;
        for(int index:order)if(!reserved.contains(index)&&binding.get(index).isEmpty()&&desired.getCount()<=binding.limit(index,desired)&&binding.valid(index,desired))return index;
        return -1;
    }
    private static Skipped missing(JEDLoadoutDefinition.Entry entry,JEDLoadoutDefinition.Address address,boolean missing){return new Skipped(entry.item(),slot(address,entry.index()),address,entry.index(),missing);}
    private static String slot(JEDLoadoutDefinition.Address address,int index){return address.backend()+":"+address.container()+"["+index+"]"+(address.cosmetic()?" (cosmetic)":"");}
    private JEDLoadoutController(){}
}
