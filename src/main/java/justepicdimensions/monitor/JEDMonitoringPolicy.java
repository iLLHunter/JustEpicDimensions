package justepicdimensions.monitor;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Reusable monitoring settings; absent policies preserve native live diagnostics. */
public record JEDMonitoringPolicy(int sampleSeconds,String mode,int detailSeconds,int retentionSeconds,int diskSeconds,List<Rule> rules) {
 public static final String DIRECTORY="justepicdimensions/monitoring";
 public static final Set<String> METRICS=Set.of("tick_ms_mean","tick_ms_p95","tick_ms_max","loaded_chunks","loaded_entities","players","disk_live_bytes","disk_history_bytes","disk_total_bytes");
 public record Rule(String id,String metric,double above,double clearBelow,int delaySeconds,int recoverySeconds,Set<String> actions){}
 public record Binding(Boolean enabled,ResourceLocation policy){}
 /** An invalid rule or policy. At startup a whole-policy problem falls back to the defaults; otherwise only that rule is left out. */
 public record Problem(String text,boolean wholePolicy){}
 public static JEDMonitoringPolicy defaults(){return new JEDMonitoringPolicy(5,"detailed",3600,604800,3600,List.of());}
 public static Binding binding(JsonObject root){if(!root.has("monitoring"))return new Binding(null,null);var j=JEDJson.object(root.get("monitoring"),"monitoring");JEDJson.keys(j,Set.of("enabled","policy"),"monitoring");Boolean enabled=null;if(j.has("enabled")){if(!j.get("enabled").isJsonPrimitive()||!j.getAsJsonPrimitive("enabled").isBoolean())throw new JEDDefinitionException("monitoring.enabled must be boolean");enabled=j.get("enabled").getAsBoolean();}return new Binding(enabled,j.has("policy")?JEDDimensionDefinition.id(JEDJson.string(j.get("policy"),"monitoring.policy"),"monitoring.policy"):null);}
 public static JEDMonitoringPolicy decode(JsonObject j){return decode(j,null);}
 /** With a problem list, an invalid rule is reported and left out instead of rejecting the whole policy. */
 static JEDMonitoringPolicy decode(JsonObject j,List<Problem> problems){
  JEDJson.keys(j,Set.of("format_version","sample_seconds","history","disk_refresh_seconds","rules"),"monitoring policy");
  if(integer(j,"format_version",0,1)!=1)throw new JEDDefinitionException("Unsupported monitoring policy version");
  var h=j.has("history")?JEDJson.object(j.get("history"),"history"):new JsonObject();
  JEDJson.keys(h,Set.of("mode","detail_seconds","retention_seconds"),"monitoring history");
  String mode=string(h,"mode","detailed");
  if(!Set.of("detailed","summarized","session").contains(mode))throw new JEDDefinitionException("history.mode must be detailed, summarized or session");
  var rules=new ArrayList<Rule>();var ids=new HashSet<String>();
  if(j.has("rules")){
   if(!j.get("rules").isJsonArray())throw new JEDDefinitionException("monitoring.rules must be an array");
   int index=0;
   for(var element:j.getAsJsonArray("rules")){
    String label="rules["+index++ +"]";
    if(element.isJsonObject()&&element.getAsJsonObject().get("id") instanceof JsonPrimitive named&&named.isString())label="rule '"+named.getAsString()+"'";
    try{rules.add(rule(element,ids));}
    catch(JEDDefinitionException error){if(problems==null)throw new JEDDefinitionException(label+": "+error.getMessage(),error);problems.add(new Problem(label+": "+error.getMessage(),false));}
   }
  }
  return new JEDMonitoringPolicy(integer(j,"sample_seconds",5,1),mode,integer(h,"detail_seconds",3600,1),integer(h,"retention_seconds",604800,1),integer(j,"disk_refresh_seconds",3600,1),List.copyOf(rules));
 }
 private static Rule rule(JsonElement element,Set<String> ids){
  var r=JEDJson.object(element,"monitoring rule");
  JEDJson.keys(r,Set.of("id","metric","above","clear_below","delay_seconds","recovery_seconds","actions"),"monitoring rule");
  String id=JEDJson.string(r.get("id"),"rule.id"),metric=JEDJson.string(r.get("metric"),"rule.metric");
  if(!id.matches("[a-z0-9_-]{1,64}")||!ids.add(id))throw new JEDDefinitionException("Unique monitoring rule id required");
  if(!METRICS.contains(metric))throw new JEDDefinitionException("Unknown monitoring metric "+metric);
  double above=number(r,"above"),below=r.has("clear_below")?number(r,"clear_below"):above;
  if(below>above)throw new JEDDefinitionException("clear_below exceeds above");
  var actions=new HashSet<String>();
  if(r.has("actions")){
   if(!r.get("actions").isJsonArray())throw new JEDDefinitionException("rule.actions must be an array");
   for(var a:r.getAsJsonArray("actions")){String action=JEDJson.string(a,"action");if(!Set.of("pause_pregeneration","unload").contains(action)||!actions.add(action))throw new JEDDefinitionException("Invalid or repeated monitoring action "+action);}
  }
  return new Rule(id,metric,above,below,integer(r,"delay_seconds",0,0),integer(r,"recovery_seconds",10,0),Set.copyOf(actions));
 }
 static int integer(JsonObject j,String key,int fallback,int minimum){int value=j.has(key)?JEDJson.integer(j.get(key),key):fallback;if(value<minimum)throw new JEDDefinitionException(key+" must be at least "+minimum);return value;}
 static String string(JsonObject j,String key,String fallback){return j.has(key)?JEDJson.string(j.get(key),key):fallback;}
 static double number(JsonObject r,String key){var e=r.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!Double.isFinite(e.getAsDouble())||e.getAsDouble()<0)throw new JEDDefinitionException(key+" must be a nonnegative finite number");return e.getAsDouble();}
 public static Map<ResourceLocation,JEDMonitoringPolicy> decodeAll(Map<ResourceLocation,JEDSourceDocument> resources){return decodeAll(resources,null);}
 /** Without a problem list the first invalid policy is rejected, naming its file and rule. With one, each problem is
  * recorded instead: an invalid rule is left out, and a policy whose own settings are invalid uses the defaults. */
 public static Map<ResourceLocation,JEDMonitoringPolicy> decodeAll(Map<ResourceLocation,JEDSourceDocument> resources,List<Problem> problems){
  var out=new TreeMap<ResourceLocation,JEDMonitoringPolicy>();
  for(var e:resources.entrySet())if(e.getKey().getPath().startsWith(DIRECTORY+"/")){
   String file="Monitoring policy "+e.getKey();var found=new ArrayList<Problem>();JEDMonitoringPolicy policy;
   try{policy=decode(JEDJson.object(e.getValue().value(),e.getKey().toString()),problems==null?null:found);}
   catch(JEDDefinitionException error){if(problems==null)throw new JEDDefinitionException(file+": "+error.getMessage(),error);found.add(new Problem(error.getMessage(),true));policy=defaults();}
   if(problems!=null)for(var problem:found)problems.add(new Problem(file+": "+problem.text(),problem.wholePolicy()));
   out.put(JEDRegistryResources.entry(e.getKey(),DIRECTORY),policy);
  }
  return Map.copyOf(out);
 }
}
