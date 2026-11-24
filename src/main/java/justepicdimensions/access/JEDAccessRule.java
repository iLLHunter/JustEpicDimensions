package justepicdimensions.access;

import com.google.gson.*;
import com.mojang.serialization.DynamicOps;
import java.util.*;
import java.util.function.Predicate;
import justepicdimensions.definition.*;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Compiled immutable condition tree; evaluation errors propagate through NOT rather than granting access. */
public record JEDAccessRule(ResourceLocation id,Node condition,JsonObject json) {
    public static final String DIRECTORY="justepicdimensions/access_rules";
    public record Result(boolean allowed,Component reason){public static final Result ALLOW=new Result(true,Component.empty());}
    public interface Node {Result evaluate(ServerPlayer player);Component description();}
    public static Component text(String key,String fallback,Object...args){return Component.translatableWithFallback("justepicdimensions.access."+key,fallback,args);}
    public static JEDAccessRule decode(ResourceLocation id,JEDSourceDocument source,DynamicOps<JsonElement> ops){
        var j=JEDJson.object(source.value(),id.toString());JEDJson.keys(j,Set.of("format_version","condition"),id.toString());
        if(JEDJson.integer(j.get("format_version"),id+".format_version")!=1)throw new JEDDefinitionException(id+": expected access rule format_version 1");
        return new JEDAccessRule(id,decodeNode(j.get("condition"),ops,id+".condition",0,new int[1]),j.deepCopy());
    }
    private static Node leaf(Predicate<ServerPlayer> test,Component description){return new Node(){public Result evaluate(ServerPlayer p){return test.test(p)?Result.ALLOW:new Result(false,description);}public Component description(){return description;}};}
    private static Component join(List<Component> values){var result=Component.empty();for(int i=0;i<values.size();i++){if(i>0)result.append("; ");result.append(values.get(i));}return result;}
    private static Node decodeNode(JsonElement input,DynamicOps<JsonElement> ops,String at,int depth,int[] count){
        if(depth>32||++count[0]>256)throw new JEDDefinitionException(at+": access trees support at most 256 nodes and depth 32");
        var j=JEDJson.object(input,at);JEDJson.keys(j,Set.of("all","any","not","advancement","score","team","teams","permission_level","permission","item"),at);
        if(j.size()!=1)throw new JEDDefinitionException(at+": expected exactly one condition kind");
        String kind=j.keySet().iterator().next();var value=j.get(kind);
        if(kind.equals("all")||kind.equals("any")){
            if(!value.isJsonArray()||value.getAsJsonArray().isEmpty())throw new JEDDefinitionException(at+": condition groups must not be empty");
            var children=new ArrayList<Node>();int index=0;for(var child:value.getAsJsonArray())children.add(decodeNode(child,ops,at+"."+kind+"["+index+++ "]",depth+1,count));
            var description=text(kind,kind.equals("all")?"All of (%s)":"Any of (%s)",join(children.stream().map(Node::description).toList()));
            return new Node(){public Component description(){return description;}public Result evaluate(ServerPlayer p){
                var failures=new ArrayList<Component>();for(var child:children){var r=child.evaluate(p);if(kind.equals("any")&&r.allowed())return Result.ALLOW;if(!r.allowed())failures.add(r.reason());}
                if(kind.equals("all")&&failures.isEmpty())return Result.ALLOW;
                return new Result(false,text(kind,kind.equals("all")?"All of (%s)":"Any of (%s)",join(failures)));
            }};
        }
        if(kind.equals("not")){
            var child=decodeNode(value,ops,at+".not",depth+1,count);var description=text("not","Not (%s)",child.description());
            return new Node(){public Component description(){return description;}public Result evaluate(ServerPlayer p){return child.evaluate(p).allowed()?new Result(false,description):Result.ALLOW;}};
        }
        if(kind.equals("teams")){
            var teams=justepicdimensions.teams.JEDTeamRule.decode(value,at+".teams");
            return leaf(teams::test,text("teams","Allowed team memberships (%s): %s",teams.each()?"each":"any",teams.allow().toString()));
        }
        if(kind.equals("team")){
            String name=JEDJson.string(value,at+".team");if(name.isBlank())throw new JEDDefinitionException(at+": team name is empty");
            return leaf(p->p.getTeam()!=null&&p.getTeam().getName().equals(name),text("team","Member of team %s",name));
        }
        if(kind.equals("permission_level")){
            int level=JEDJson.integer(value,at);if(level<0||level>4)throw new JEDDefinitionException(at+": permission level must be 0..4");
            return leaf(p->p.hasPermissions(level),text("level","Permission level at least %s",level));
        }
        var settings=JEDJson.object(value,at+"."+kind);
        if(kind.equals("score")){
            JEDJson.keys(settings,Set.of("objective","min","max"),at);String objective=JEDJson.string(settings.get("objective"),at+".objective");
            if(objective.isBlank()||(!settings.has("min")&&!settings.has("max")))throw new JEDDefinitionException(at+": score needs an objective and min/max");
            int min=integer(settings,"min",Integer.MIN_VALUE,at),max=integer(settings,"max",Integer.MAX_VALUE,at);if(min>max)throw new JEDDefinitionException(at+": score min exceeds max");
            return leaf(p->{var board=p.server.getScoreboard();var field=board.getObjective(objective);var score=field==null?null:board.getPlayerScoreInfo(p,field);return score!=null&&score.value()>=min&&score.value()<=max;},text("score","Score %s in range %s..%s",objective,min,max));
        }
        if(kind.equals("advancement")){
            JEDJson.keys(settings,Set.of("id","complete","criteria"),at);var id=JEDDimensionDefinition.id(JEDJson.string(settings.get("id"),at+".id"),at);
            Boolean complete=settings.has("complete")||!settings.has("criteria")?JEDAccessDefinition.flag(settings,"complete",true,at):null;
            var criteria=new TreeMap<String,Boolean>();if(settings.has("criteria")){
                var values=JEDJson.object(settings.get("criteria"),at+".criteria");if(values.isEmpty())throw new JEDDefinitionException(at+": criteria must not be empty");
                for(var key:values.keySet())criteria.put(key,JEDAccessDefinition.flag(values,key,false,at+".criteria"));
            }
            return leaf(p->{var advancement=p.server.getAdvancements().get(id);if(advancement==null)throw new JEDDefinitionException("Unknown access advancement "+id);
                for(var key:criteria.keySet())if(!advancement.value().criteria().containsKey(key))throw new JEDDefinitionException("Unknown access advancement criterion "+id+"/"+key);
                var progress=p.getAdvancements().getOrStartProgress(advancement);if(complete!=null&&progress.isDone()!=complete)return false;
                for(var entry:criteria.entrySet())if(progress.getCriterion(entry.getKey()).isDone()!=entry.getValue())return false;return true;
            },text("advancement","Advancement %s (%s)",id.toString(),criteria.isEmpty()?String.valueOf(complete):criteria.toString()+(complete==null?"":"; complete="+complete)));
        }
        if(kind.equals("permission")){
            JEDJson.keys(settings,Set.of("provider","node","value"),at);String provider=JEDAccessDefinition.choice(settings,"provider","neoforge",Set.of("neoforge","luckperms"),at);
            String name=JEDJson.string(settings.get("node"),at+".node");if(name.isBlank())throw new JEDDefinitionException(at+": permission node is empty");
            boolean expected=JEDAccessDefinition.flag(settings,"value",true,at);
            return leaf(p->JEDAccessPermissions.test(p,provider,name)==expected,text("permission","Permission %s:%s = %s",provider,name,expected));
        }
        JEDJson.keys(settings,Set.of("predicate","min","max","locations"),at);
        var predicateJson=JEDJson.object(settings.get("predicate"),at+".predicate");JEDJson.keys(predicateJson,Set.of("items","count","components","predicates"),at+".predicate");
        var predicate=ItemPredicate.CODEC.parse(ops,predicateJson).getOrThrow(error->new JEDDefinitionException(at+": "+error));
        int min=integer(settings,"min",1,at),max=integer(settings,"max",Integer.MAX_VALUE,at);if(min<0||max<min)throw new JEDDefinitionException(at+": item quantity requires 0 <= min <= max");
        var locations=JEDAccessItems.locations(settings.get("locations"),at+".locations");
        return leaf(p->{long total=0;for(var stack:JEDAccessItems.stacks(p,locations))if(!stack.isEmpty()&&predicate.test(stack))total+=stack.getCount();return total>=min&&total<=max;},itemDescription(predicate,predicateJson,min,max));
    }
    private static Component itemDescription(ItemPredicate predicate,JsonObject raw,int min,int max){
        Component name=text("matching_item","matching items");var items=raw.get("items");
        if(items!=null&&items.isJsonPrimitive()&&items.getAsString().startsWith("#"))name=text("item_tag","items tagged %s",items.getAsString());
        else if(predicate.items().isPresent()){
            var names=new ArrayList<Component>();for(var item:predicate.items().get()){
                var preview=new net.minecraft.world.item.ItemStack(item.value());preview.applyComponents(predicate.components().asPatch());names.add(preview.getHoverName());
            }
            if(!names.isEmpty())name=join(names);
        }
        if(raw.has("components")||raw.has("predicates"))name=text("item_properties","%s with the required item data",name);
        return max==Integer.MAX_VALUE?text("item_min","At least %s of %s",min,name):text("item_range","%s..%s of %s",min,max,name);
    }
    private static int integer(JsonObject j,String key,int fallback,String at){return j.has(key)?JEDJson.integer(j.get(key),at+"."+key):fallback;}
}
