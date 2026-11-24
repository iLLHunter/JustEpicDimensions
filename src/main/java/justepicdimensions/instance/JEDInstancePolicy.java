package justepicdimensions.instance;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.schedule.JEDLifecycleSchedulePolicy;
import net.minecraft.resources.ResourceLocation;

/** Reusable policy; ownership and progress are always save-specific. */
public record JEDInstancePolicy(ResourceLocation id, String ownership, boolean multiple, boolean automatic,
        boolean snapshot, String seedMode, String fixedSeed, String exit, Map<String, Permission> permissions,
        Set<String> scoped, int registeredLimit, int loadedLimit, String lifetime,
        JEDLifecycleSchedulePolicy.Rule timer, JEDLifecycleSchedulePolicy.Retention retention) {
    public static final String DIRECTORY="justepicdimensions/instance_policies";
    public static final Set<String> ACTIONS=Set.of("create","reset","retire","members","managers");
    public record Permission(String mode,int level,ResourceLocation rule) {}
    public JEDInstancePolicy { permissions=Map.copyOf(permissions);scoped=Set.copyOf(scoped); }
    public static JEDInstancePolicy decode(ResourceLocation id,JEDSourceDocument document) {
        var root=JEDJson.object(document.value(),id.toString());
        JEDJson.keys(root,Set.of("format_version","ownership","runs","routing","gameplay_updates","seed","exit","permissions","state_scope","limits","lifetime","retention"),id.toString());
        if(JEDJson.integer(root.get("format_version"),id+".format_version")!=1)throw new JEDDefinitionException(id+": expected instance policy format_version 1");
        String ownership=choice(root,"ownership","personal",Set.of("personal","team","group"));
        String runs=choice(root,"runs","single",Set.of("single","multiple"));
        String routing=choice(root,"routing","explicit",Set.of("explicit","automatic"));
        String updates=choice(root,"gameplay_updates","follow",Set.of("follow","snapshot"));
        String exit=choice(root,"exit","entry",Set.of("entry","fallback"));
        var seed=object(root,"seed");JEDJson.keys(seed,Set.of("mode","value"),id+".seed");
        String seedMode=choice(seed,"mode","source",Set.of("source","fixed","random"));
        String fixed=seed.has("value")?JEDJson.string(seed.get("value"),id+".seed.value"):null;
        if(seedMode.equals("fixed")?(fixed==null||fixed.isBlank()):fixed!=null)throw new JEDDefinitionException(id+": seed.value is required only for fixed seeds");
        var permissions=new TreeMap<String,Permission>();var rawPermissions=object(root,"permissions");JEDJson.keys(rawPermissions,ACTIONS,id+".permissions");
        for(String action:ACTIONS){var raw=object(rawPermissions,action);JEDJson.keys(raw,Set.of("mode","level","rule"),id+".permissions."+action);String mode=choice(raw,"mode","operators",Set.of("operators","members","managers"));int level=integer(raw,"level",2,0,4);ResourceLocation rule=raw.has("rule")?JEDDimensionDefinition.id(JEDJson.string(raw.get("rule"),"rule"),id.toString()):null;permissions.put(action,new Permission(mode,level,rule));}
        var scope=object(root,"state_scope");var categories=new TreeSet<String>(Set.of("inventory"));for(var category:justepicdimensions.player.JEDPlayerCategory.values())categories.add(category.key());JEDJson.keys(scope,categories,id+".state_scope");var scoped=new TreeSet<String>();for(String category:categories)if(choice(scope,category,"inherit",Set.of("inherit","instance")).equals("instance"))scoped.add(category);
        var limits=object(root,"limits");JEDJson.keys(limits,Set.of("registered","loaded"),id+".limits");int registered=integer(limits,"registered",Integer.MAX_VALUE,1,65536),loaded=integer(limits,"loaded",Integer.MAX_VALUE,1,65536);
        var life=object(root,"lifetime");JEDJson.keys(life,Set.of("mode","schedule"),id+".lifetime");String lifetime=choice(life,"mode","persistent",Set.of("persistent","empty","timed","after_exit"));
        boolean scheduled=lifetime.equals("empty")||lifetime.equals("timed");
        if(!scheduled&&life.has("schedule"))throw new JEDDefinitionException(id+": "+lifetime+" instances cannot have a cleanup schedule");
        var wrapper=new JsonObject();wrapper.addProperty("format_version",1);var rules=new JsonArray();var timer=life.has("schedule")?JEDJson.object(life.get("schedule"),id+".lifetime.schedule").deepCopy():new JsonObject();
        if(scheduled&&!life.has("schedule"))throw new JEDDefinitionException(id+": empty/timed cleanup requires an explicit schedule");
        if(life.has("schedule")&&(timer.has("id")||timer.has("action")))throw new JEDDefinitionException(id+": cleanup schedule id/action are owned by the instance policy");
        timer.addProperty("id","instance_cleanup");timer.addProperty("action","reset");if(!scheduled){timer.addProperty("clock","real_time");timer.addProperty("interval_seconds",1);}
        if(root.has("retention"))timer.add("retention",root.get("retention"));rules.add(timer);wrapper.add("rules",rules);
        var parsed=JEDLifecycleSchedulePolicy.decode(id,new JEDSourceDocument(wrapper,id+" instance cleanup")).rules().getFirst();
        return new JEDInstancePolicy(id,ownership,runs.equals("multiple"),routing.equals("automatic"),updates.equals("snapshot"),seedMode,fixed,exit,permissions,scoped,registered,loaded,lifetime,scheduled?parsed:null,parsed.retention());
    }
    public static Map<ResourceLocation,JEDInstancePolicy> decodeAll(Map<ResourceLocation,JEDSourceDocument> resources){var result=new TreeMap<ResourceLocation,JEDInstancePolicy>();resources.forEach((key,doc)->{if(key.getPath().startsWith(DIRECTORY+"/"))result.put(JEDRegistryResources.entry(key,DIRECTORY),decode(JEDRegistryResources.entry(key,DIRECTORY),doc));});return Map.copyOf(result);}
    private static JsonObject object(JsonObject root,String key){return root.has(key)?JEDJson.object(root.get(key),key):new JsonObject();}
    private static String choice(JsonObject root,String key,String fallback,Set<String> choices){String value=root.has(key)?JEDJson.string(root.get(key),key):fallback;if(!choices.contains(value))throw new JEDDefinitionException(key+": choose "+new TreeSet<>(choices));return value;}
    private static int integer(JsonObject root,String key,int fallback,int min,int max){if(!root.has(key))return fallback;int value=JEDJson.integer(root.get(key),key);if(value<min||value>max)throw new JEDDefinitionException(key+": expected "+min+".."+max);return value;}
}
