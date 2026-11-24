package justepicdimensions.environment;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.player.*;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;

/** Match actual worn slots; a physical slot cannot count twice through backend aliases. */
public record JEDEnvironmentProtection(String stacking,List<Piece> pieces){
    public record Piece(ItemPredicate predicate,Set<String> slots,boolean immune,double reduction){}
    public record Result(boolean immune,double remaining){public static final Result NONE=new Result(false,1);}
    private record Worn(Object owner,String part,int index,String address,ItemStack item){
        public int hashCode(){return System.identityHashCode(owner)*31+part.hashCode()*17+index;}
        public boolean equals(Object other){return other instanceof Worn w&&owner==w.owner&&part.equals(w.part)&&index==w.index;}
    }
    public static JEDEnvironmentProtection decode(JsonElement raw,DynamicOps<JsonElement> ops,String at){
        if(raw==null)return new JEDEnvironmentProtection("strongest",List.of());var j=JEDJson.object(raw,at);JEDJson.keys(j,Set.of("stacking","items"),at);String stacking=JEDEnvironmentPolicy.choice(j,"stacking","strongest",Set.of("strongest","multiply","add"),at);var pieces=new ArrayList<Piece>();int i=0;
        for(var entry:JEDEnvironmentPolicy.array(j,"items",at)){
            String here=at+".items["+i+++"]";var piece=JEDJson.object(entry,here);JEDJson.keys(piece,Set.of("predicate","slots","immune","damage_reduction_percent"),here);
            var predicateJson=JEDJson.object(piece.get("predicate"),here+".predicate");JEDJson.keys(predicateJson,Set.of("items","count","components","predicates"),here+".predicate");
            var predicate=ItemPredicate.CODEC.parse(ops,predicateJson).getOrThrow(error->new JEDDefinitionException(here+": "+error));
            boolean immune=JEDEnvironmentPolicy.flag(piece,"immune",false,here);double reduction=piece.has("damage_reduction_percent")?JEDEnvironmentPolicy.number(piece.get("damage_reduction_percent"),here):0;if(reduction<0||reduction>100)throw new JEDDefinitionException(here+": reduction must be 0..100 percent");if(!immune&&reduction==0)throw new JEDDefinitionException(here+": protection needs immunity or positive damage reduction");
            var slots=new HashSet<String>();for(var slot:JEDEnvironmentPolicy.array(piece,"slots",here)){String name=JEDJson.string(slot,here+".slots");var parsed=JEDDimensionDefinition.id(name,here);if(parsed.getNamespace().equals("minecraft")&&!Set.of("mainhand","offhand","head","chest","legs","feet").contains(parsed.getPath()))throw new JEDDefinitionException(here+": unknown native worn slot "+name);slots.add(parsed.toString());}
            pieces.add(new Piece(predicate,Set.copyOf(slots),immune,reduction/100));
        }
        return new JEDEnvironmentProtection(stacking,List.copyOf(pieces));
    }
    public Result test(LivingEntity entity){
        if(pieces.isEmpty())return Result.NONE;var worn=new ArrayList<Worn>();
        for(var slot:List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){var item=entity.getItemBySlot(slot);if(!item.isEmpty())worn.add(new Worn(entity,slot.getName(),0,"minecraft:"+slot.getName(),item));}
        if(entity instanceof ServerPlayer player)for(var adapter:JEDInventoryAdapterRegistry.adapters())if(!adapter.id().equals("minecraft"))for(var equipment:adapter.equipped(player)){
            var binding=JEDInventoryAdapterRegistry.resolve(player,new JEDLoadoutDefinition.Address(equipment.backend(),equipment.slot(),false));if(binding!=null&&equipment.index()<binding.size())worn.add(new Worn(binding.owner(),binding.part(),equipment.index(),equipment.backend()+":"+equipment.slot(),equipment.item()));
        }
        if(entity instanceof ServerPlayer player){
            var aliases=new HashSet<String>();for(var piece:pieces)aliases.addAll(piece.slots());
            var physical=List.copyOf(worn);for(String alias:aliases){var split=alias.indexOf(':');if(alias.startsWith("minecraft:"))continue;var binding=JEDInventoryAdapterRegistry.resolve(player,new JEDLoadoutDefinition.Address(alias.substring(0,split),alias.substring(split+1),false));if(binding==null)continue;
                for(var slot:physical)if(slot.owner()==binding.owner()&&slot.part().equals(binding.part())&&slot.index()<binding.size())worn.add(new Worn(slot.owner(),slot.part(),slot.index(),alias,slot.item()));
            }
        }
        // Match aliases before deduplication: a Curios address may name a physical Accessories slot.
        var reductions=new LinkedHashMap<Worn,Double>();
        for(var slot:worn){double best=0;for(var piece:pieces)if((piece.slots().isEmpty()||piece.slots().contains(slot.address()))&&piece.predicate().test(slot.item())){if(piece.immune())return new Result(true,0);best=Math.max(best,piece.reduction());}reductions.merge(slot,best,Math::max);}
        double strongest=0,remaining=1,sum=0;
        for(double best:reductions.values()){strongest=Math.max(strongest,best);remaining*=1-best;sum+=best;}
        return new Result(false,switch(stacking){case "multiply"->remaining;case "add"->Math.max(0,1-sum);default->1-strongest;});
    }
}
