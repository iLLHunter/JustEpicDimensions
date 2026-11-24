package justepicdimensions.access;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.player.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Read-only inventory views, with physical-slot identity preventing overlapping selectors from double counting. */
public final class JEDAccessItems {
    private static final Set<String> LOCATIONS=Set.of("inventory","hotbar","mainhand","offhand","armor","accessories","ender_chest");
    public static List<JsonElement> locations(JsonElement input,String at){
        if(input==null)return List.of(new JsonPrimitive("inventory"),new JsonPrimitive("offhand"),new JsonPrimitive("armor"),new JsonPrimitive("accessories"));
        if(!input.isJsonArray()||input.getAsJsonArray().isEmpty())throw new JEDDefinitionException(at+": expected a nonempty location array");
        var result=new ArrayList<JsonElement>();
        for(var v:input.getAsJsonArray()){
            if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()){
                if(!LOCATIONS.contains(v.getAsString()))throw new JEDDefinitionException(at+": unknown item location "+v);
            }else{
                var j=JEDJson.object(v,at);JEDJson.keys(j,Set.of("backend","container","cosmetic","slot"),at);
                if(!Set.of("curios","accessories").contains(JEDJson.string(j.get("backend"),at+".backend")))throw new JEDDefinitionException(at+": expected curios or accessories backend");
                if(JEDJson.string(j.get("container"),at+".container").isBlank())throw new JEDDefinitionException(at+": container is empty");
                JEDAccessDefinition.flag(j,"cosmetic",false,at);
                if(j.has("slot")&&JEDJson.integer(j.get("slot"),at+".slot")<0)throw new JEDDefinitionException(at+": slot must be nonnegative");
            }
            result.add(v.deepCopy());
        }
        return List.copyOf(result);
    }
    private static final class Slot {
        final Object owner;final String part;final int index;final ItemStack value;
        Slot(Object owner,String part,int index,ItemStack value){this.owner=owner;this.part=part;this.index=index;this.value=value;}
        public int hashCode(){return System.identityHashCode(owner)*31+part.hashCode()*17+index;}
        public boolean equals(Object other){return other instanceof Slot s&&owner==s.owner&&part.equals(s.part)&&index==s.index;}
    }
    public static List<ItemStack> stacks(ServerPlayer player,List<JsonElement> locations){
        var slots=new LinkedHashSet<Slot>();var inventory=player.getInventory();
        for(var selector:locations){
            if(selector.isJsonPrimitive())switch(selector.getAsString()){
                case "inventory"->{for(int i=0;i<inventory.items.size();i++)slots.add(new Slot(inventory,"main",i,inventory.items.get(i)));}
                case "hotbar"->{for(int i=0;i<Math.min(9,inventory.items.size());i++)slots.add(new Slot(inventory,"main",i,inventory.items.get(i)));}
                case "mainhand"->slots.add(new Slot(inventory,"main",inventory.selected,player.getMainHandItem()));
                case "offhand"->{for(int i=0;i<inventory.offhand.size();i++)slots.add(new Slot(inventory,"offhand",i,inventory.offhand.get(i)));}
                case "armor"->{for(int i=0;i<inventory.armor.size();i++)slots.add(new Slot(inventory,"armor",i,inventory.armor.get(i)));}
                case "ender_chest"->{var chest=player.getEnderChestInventory();for(int i=0;i<chest.getContainerSize();i++)slots.add(new Slot(chest,"ender",i,chest.getItem(i)));}
                case "accessories"->{for(var adapter:JEDInventoryAdapterRegistry.adapters())if(!adapter.id().equals("minecraft"))
                    for(var equipment:adapter.equipped(player))binding(slots,player,new JEDLoadoutDefinition.Address(equipment.backend(),equipment.slot(),false),equipment.index());}
            }else{
                var j=selector.getAsJsonObject();var address=new JEDLoadoutDefinition.Address(j.get("backend").getAsString(),j.get("container").getAsString(),j.has("cosmetic")&&j.get("cosmetic").getAsBoolean());
                binding(slots,player,address,j.has("slot")?j.get("slot").getAsInt():-1);
            }
        }
        return slots.stream().map(s->s.value).toList();
    }
    private static void binding(Set<Slot> slots,ServerPlayer player,JEDLoadoutDefinition.Address address,int index){
        var binding=JEDInventoryAdapterRegistry.resolve(player,address);if(binding==null)return;
        if(index>=0){if(index<binding.size())slots.add(new Slot(binding.owner(),binding.part(),index,binding.get(index)));}
        else for(int i=0;i<binding.size();i++)slots.add(new Slot(binding.owner(),binding.part(),i,binding.get(i)));
    }
    private JEDAccessItems(){}
}
