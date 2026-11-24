package justepicdimensions.schedule;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;

/** Detached schedule progress snapshots. Only immutable copies cross the IO worker boundary. */
public final class JEDScheduleStore {
    public static Path file(Path world){return world.resolve("data/justepicdimensions/lifecycle_schedules.json");}
    public static String key(ResourceLocation dimension,ResourceLocation policy,String rule){return dimension+"|"+policy+"|"+rule;}
    public static ResourceLocation dimension(String key){var parts=key.split("\\|",-1);if(parts.length!=3)throw new JEDDefinitionException("Invalid saved schedule key "+key);var dim=JEDDimensionDefinition.id(parts[0],"schedule dimension");var policy=JEDDimensionDefinition.id(parts[1],"schedule policy");JEDDimensionDefinition.id(policy.getNamespace()+":"+parts[2],"schedule rule");return dim;}
    public static JsonObject empty(){var root=new JsonObject();root.addProperty("format_version",1);root.addProperty("server_ticks",0L);root.add("states",new JsonObject());return root;}
    public static JsonObject read(Path world){var path=file(world);if(!Files.exists(path))return empty();var root=JEDJson.object(JEDJson.read(path),path.toString());validate(root);return root.deepCopy();}
    public static void write(Path world,JsonObject snapshot){validate(snapshot);JEDDimensionFiles.requireSafe(world,file(world),false);JEDDimensionFiles.writeDurable(file(world),snapshot);}
    public static void validate(JsonObject root){
        JEDJson.keys(root,Set.of("format_version","server_ticks","states"),"schedule progress");if(JEDJson.integer(root.get("format_version"),"schedule progress version")!=1)throw new JEDDefinitionException("Unsupported schedule progress format");JEDLifecycleSchedulePolicy.nonnegative(root.get("server_ticks"),"saved schedule ticks");
        var states=JEDJson.object(root.get("states"),"schedule states");for(var entry:states.entrySet()){
            dimension(entry.getKey());var value=JEDJson.object(entry.getValue(),entry.getKey());JEDJson.keys(value,Set.of("signature","clock","action","active","activated_at","next_due","last_completed","pending_due","warning_end","last_notice","open_until","first_open","was_open","native_token","native_due","blocked_reason"),entry.getKey());
            JEDJson.string(value.get("signature"),"saved schedule signature");JEDLifecycleSchedulePolicy.choice(value,"clock",null,Set.of("calendar","real_time","server_ticks"),entry.getKey());JEDLifecycleSchedulePolicy.nonnegative(value.get("next_due"),"saved next deadline");
            for(String key:List.of("activated_at","last_completed","pending_due","warning_end","last_notice","open_until","first_open","native_due"))if(value.has(key))JEDLifecycleSchedulePolicy.nonnegative(value.get(key),entry.getKey()+"."+key);
            if(value.has("active"))JEDLifecycleSchedulePolicy.flag(value,"active",entry.getKey());if(value.has("action"))JEDLifecycleSchedulePolicy.choice(value,"action",null,Set.of("reset","availability"),entry.getKey());
            if(value.has("was_open"))JEDLifecycleSchedulePolicy.flag(value,"was_open",entry.getKey());if(value.has("native_token"))token(JEDJson.string(value.get("native_token"),"saved operation token"));if(value.has("blocked_reason"))JEDJson.string(value.get("blocked_reason"),"saved blocker");
        }
    }
    public static void validateMetadata(JsonObject metadata,ResourceLocation target){
        JEDJson.keys(metadata,Set.of("format_version","jobs"),"scheduled lifecycle intent");if(JEDJson.integer(metadata.get("format_version"),"scheduled lifecycle version")!=1)throw new JEDDefinitionException("Invalid scheduled lifecycle metadata version");
        var jobs=metadata.get("jobs");if(jobs==null||!jobs.isJsonArray()||jobs.getAsJsonArray().isEmpty())throw new JEDDefinitionException("Scheduled lifecycle metadata requires jobs");var keys=new HashSet<String>();
        for(var raw:jobs.getAsJsonArray()){var job=JEDJson.object(raw,"scheduled job");JEDJson.keys(job,Set.of("key","due","clock","retention"),"scheduled job");String key=JEDJson.string(job.get("key"),"scheduled job key");if(!dimension(key).equals(target)||!keys.add(key))throw new JEDDefinitionException("Scheduled lifecycle job identity mismatch");JEDLifecycleSchedulePolicy.nonnegative(job.get("due"),"scheduled due time");JEDLifecycleSchedulePolicy.choice(job,"clock",null,Set.of("calendar","real_time","server_ticks"),key);retention(JEDJson.object(job.get("retention"),"scheduled retention"));}
    }
    public static JEDLifecycleSchedulePolicy.Retention retention(JsonObject json){JEDJson.keys(json,Set.of("mode","value"),"saved schedule retention");String mode=JEDLifecycleSchedulePolicy.choice(json,"mode",null,Set.of("age","count","unlimited"),"saved schedule retention");long value=JEDLifecycleSchedulePolicy.nonnegative(json.get("value"),"saved retention value");if(!mode.equals("unlimited")&&value==0)throw new JEDDefinitionException("Saved retention limit must be positive");if(mode.equals("age"))try{Math.multiplyExact(value,86400000);}catch(ArithmeticException error){throw new JEDDefinitionException("Saved retention age exceeds clock range",error);}return new JEDLifecycleSchedulePolicy.Retention(mode,value);}
    public static void validateCompletion(String key,JsonObject receipt){
        var dimension=dimension(key);if(receipt.has("status"))JEDLifecycleSchedulePolicy.choice(receipt,"status",null,Set.of("completed","aborted"),key);JEDJson.keys(receipt,Set.of("token","dimension","due","clock","completed_at","completed_ticks","status"),"scheduled completion");token(JEDJson.string(receipt.get("token"),"completion token"));if(!dimension.toString().equals(JEDJson.string(receipt.get("dimension"),"completion dimension")))throw new JEDDefinitionException("Scheduled completion dimension mismatch");for(String field:List.of("due","completed_at","completed_ticks"))JEDLifecycleSchedulePolicy.nonnegative(receipt.get(field),"scheduled completion "+field);JEDLifecycleSchedulePolicy.choice(receipt,"clock",null,Set.of("calendar","real_time","server_ticks"),key);
    }
    public static String token(String value){try{var parsed=UUID.fromString(value);if(!parsed.toString().equals(value))throw new IllegalArgumentException();return value;}catch(IllegalArgumentException error){throw new JEDDefinitionException("Invalid scheduled lifecycle token "+value,error);}}
    private JEDScheduleStore(){}
}
