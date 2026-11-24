package justepicdimensions.spawn;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Modern dimension-scoped spawn, bed and death-routing policies. */
public record JEDSpawnDefinition(Search spawn, Sleep sleep, Respawn respawn) {
    /** {@code ignoreSuitability} is null when unset; the search then defaults it by generator (true for flat). */
    public record Search(String mode,Integer x,Integer y,Integer z,Integer minY,Integer maxY,float angle,boolean fallback,Boolean ignoreSuitability,int yOffset,boolean resetVelocity,boolean resetFallDistance) {
        public Search(String mode,Integer x,Integer y,Integer z,Integer minY,Integer maxY,float angle,boolean fallback,boolean ignoreSuitability){this(mode,x,y,z,minY,maxY,angle,fallback,Boolean.valueOf(ignoreSuitability),0,false,false);}
    }
    public record Sleep(String mode,Boolean checkpoints) {}
    public record Respawn(Boolean allowHere,ResourceLocation destination,String priority) {}
    public static JEDSpawnDefinition decode(JsonObject root,String path) {
        Search spawn=null;Sleep sleep=null;Respawn respawn=null;
        if(root.has("spawn")) {
            var j=JEDJson.object(root.get("spawn"),path+".spawn");JEDJson.keys(j,Set.of("mode","x","y","z","min_y","max_y","angle","fallback_block","ignore_suitability","y_offset","reset_velocity","reset_fall_distance"),path+".spawn");
            String mode=choice(j,"mode","native",Set.of("native","fixed","surface","cavern","none"),path);
            Integer x=integer(j,"x",path),y=integer(j,"y",path),z=integer(j,"z",path),min=integer(j,"min_y",path),max=integer(j,"max_y",path);
            if((x==null)!=(z==null))throw new JEDDefinitionException(path+": spawn.x and spawn.z must be specified together");
            if(x!=null&&(Math.abs((long)x)>29_999_984||Math.abs((long)z)>29_999_984))throw new JEDDefinitionException(path+": spawn coordinates exceed native world bounds");
            if(mode.equals("fixed")&&(x==null||y==null))throw new JEDDefinitionException(path+": fixed spawn requires x, y and z");
            if(y!=null&&!mode.equals("fixed"))throw new JEDDefinitionException(path+": spawn.y requires fixed mode; use min_y/max_y for searches");
            if(min!=null&&max!=null&&min>max)throw new JEDDefinitionException(path+": spawn.min_y exceeds max_y");
            float angle=0;if(j.has("angle")){var a=j.get("angle");if(!a.isJsonPrimitive()||!a.getAsJsonPrimitive().isNumber()||!Float.isFinite(a.getAsFloat()))throw new JEDDefinitionException(path+": angle must be finite");angle=a.getAsFloat();}
            spawn=new Search(mode,x,y,z,min,max,angle,bool(j,"fallback_block",false,path),j.has("ignore_suitability")?Boolean.valueOf(bool(j,"ignore_suitability",false,path)):null,j.has("y_offset")?JEDJson.integer(j.get("y_offset"),path+".spawn.y_offset"):0,bool(j,"reset_velocity",false,path),bool(j,"reset_fall_distance",false,path));
        }
        if(root.has("sleep")) {
            var j=JEDJson.object(root.get("sleep"),path+".sleep");JEDJson.keys(j,Set.of("mode","bed_checkpoints"),path+".sleep");
            sleep=new Sleep(choice(j,"mode","native",Set.of("native","allow","deny","explode"),path),j.has("bed_checkpoints")?bool(j,"bed_checkpoints",false,path):null);
        }
        if(root.has("respawn")) {
            var j=JEDJson.object(root.get("respawn"),path+".respawn");JEDJson.keys(j,Set.of("allow_here","destination","priority"),path+".respawn");
            respawn=new Respawn(j.has("allow_here")?bool(j,"allow_here",false,path):null,j.has("destination")?JEDDimensionDefinition.id(JEDJson.string(j.get("destination"),path+".respawn.destination"),path):null,choice(j,"priority","destination_first",Set.of("destination_first","saved_point_first"),path));
        }
        return new JEDSpawnDefinition(spawn,sleep,respawn);
    }
    static String choice(JsonObject j,String key,String fallback,Set<String> allowed,String path){String value=j.has(key)?JEDJson.string(j.get(key),path+"."+key):fallback;if(!allowed.contains(value))throw new JEDDefinitionException(path+"."+key+": expected one of "+allowed);return value;}
    static Integer integer(JsonObject j,String key,String path){return j.has(key)?JEDJson.integer(j.get(key),path+"."+key):null;}
    static boolean bool(JsonObject j,String key,boolean fallback,String path){if(!j.has(key))return fallback;var v=j.get(key);if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(path+"."+key+": expected boolean");return v.getAsBoolean();}
}
