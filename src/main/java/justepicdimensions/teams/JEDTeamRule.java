package justepicdimensions.teams;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** A positive allow section per system, with any/each across the configured systems. */
public record JEDTeamRule(boolean each,Map<String,List<Reference>> allow) {
    public record Reference(UUID id,String name){}
    public record Match(boolean allowed,List<JEDTeamSystems.Group> groups,boolean unavailable){}
    public JEDTeamRule {var copy=new LinkedHashMap<String,List<Reference>>();allow.forEach((k,v)->copy.put(k,List.copyOf(v)));allow=Collections.unmodifiableMap(copy);}
    public static JEDTeamRule decode(JsonElement value,String at){
        var root=JEDJson.object(value,at);JEDJson.keys(root,Set.of("mode","allow"),at);
        String mode=root.has("mode")?JEDJson.string(root.get("mode"),at+".mode"):"any";
        if(!Set.of("any","each").contains(mode))throw new JEDDefinitionException(at+": mode must be any or each");
        var sections=JEDJson.object(root.get("allow"),at+".allow");if(sections.isEmpty())throw new JEDDefinitionException(at+": add at least one team system");
        JEDJson.keys(sections,Set.copyOf(JEDTeamSystems.SYSTEMS),at+".allow");var result=new LinkedHashMap<String,List<Reference>>();
        for(String system:JEDTeamSystems.SYSTEMS)if(sections.has(system)){
            var raw=sections.get(system);if(!raw.isJsonArray()||raw.getAsJsonArray().isEmpty())throw new JEDDefinitionException(at+": system allow lists cannot be empty");
            var refs=new ArrayList<Reference>();for(var item:raw.getAsJsonArray()){
                var entry=JEDJson.object(item,at+"."+system);JEDJson.keys(entry,Set.of("id","name"),at);if(entry.size()!=1)throw new JEDDefinitionException(at+": each group needs exactly one id or name");
                if(entry.has("id")){try{refs.add(new Reference(UUID.fromString(JEDJson.string(entry.get("id"),at)),null));}catch(IllegalArgumentException bad){throw new JEDDefinitionException(at+": invalid group UUID",bad);}}
                else{String name=JEDJson.string(entry.get("name"),at);if(name.isBlank())throw new JEDDefinitionException(at+": empty group name");refs.add(new Reference(null,name));}
            }result.put(system,refs);
        }
        return new JEDTeamRule(mode.equals("each"),result);
    }
    public Match match(MinecraftServer server,UUID player){
        return match(server,player,null);
    }
    private Match match(MinecraftServer server,UUID player,ServerPlayer actor){
        boolean any=false,all=true,missing=false;var matches=new LinkedHashMap<JEDTeamSystems.Key,JEDTeamSystems.Group>();
        for(var section:allow.entrySet()){
            if(!JEDTeamSystems.installed(section.getKey())){all=false;missing=true;continue;}
            var groups=JEDTeamSystems.groups(server,section.getKey());boolean matched=false;
            for(var reference:section.getValue()){
                var selected=groups.stream().filter(g->reference.id()!=null?g.key().id().equals(reference.id()):g.name().equals(reference.name())).toList();
                if(reference.name()!=null&&selected.size()>1)throw new JEDDefinitionException("Ambiguous "+section.getKey()+" team name '"+reference.name()+"'; select a group ID: "+selected.stream().map(g->g.key().id().toString()).toList());
                for(var group:selected)if(actor==null?JEDTeamSystems.member(server,group,player):JEDTeamSystems.member(actor,group)){matches.put(group.key(),group);matched=true;}
            }
            any|=matched;all&=matched;
        }
        return new Match(each?all:any,List.copyOf(matches.values()),missing);
    }
    public boolean test(ServerPlayer player){var match=match(player.server,player.getUUID(),player);if(!match.allowed()&&match.unavailable())throw new JEDDefinitionException("A configured team system is unavailable");return match.allowed();}
    /** Team-only projection keeps inventory/advancement checks at their existing arrival boundaries. */
    public static Optional<Boolean> eligibility(JsonObject node,ServerPlayer player){
        if(node.has("teams"))return Optional.of(decode(node.get("teams"),"teams").test(player));
        if(node.has("not"))return eligibility(node.getAsJsonObject("not"),player).map(v->!v);
        for(String kind:List.of("all","any"))if(node.has(kind)){var children=new ArrayList<Boolean>();for(var child:node.getAsJsonArray(kind))eligibility(child.getAsJsonObject(),player).ifPresent(children::add);return children.isEmpty()?Optional.empty():Optional.of(kind.equals("all")?children.stream().allMatch(Boolean::booleanValue):children.stream().anyMatch(Boolean::booleanValue));}
        return Optional.empty();
    }
    public static boolean contains(JsonObject node){if(node.has("teams"))return true;if(node.has("not"))return contains(node.getAsJsonObject("not"));for(String kind:List.of("all","any"))if(node.has(kind))for(var child:node.getAsJsonArray(kind))if(contains(child.getAsJsonObject()))return true;return false;}
    public static List<JEDTeamSystems.Group> choices(JsonObject node,ServerPlayer player){
        var result=new LinkedHashMap<JEDTeamSystems.Key,JEDTeamSystems.Group>();collect(node,player,result,false);return List.copyOf(result.values());
    }
    private static void collect(JsonObject node,ServerPlayer player,Map<JEDTeamSystems.Key,JEDTeamSystems.Group> result,boolean negated){
        if(node.has("teams")&&!negated){var matched=decode(node.get("teams"),"teams").match(player.server,player.getUUID(),player);if(matched.allowed())for(var g:matched.groups())result.put(g.key(),g);}
        if(node.has("not"))collect(node.getAsJsonObject("not"),player,result,!negated);
        for(String kind:List.of("all","any"))if(node.has(kind))for(var child:node.getAsJsonArray(kind))collect(child.getAsJsonObject(),player,result,negated);
    }
}
