package justepicdimensions.access;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Per-dimension admission behavior. Reusable condition trees are separate resources. */
public record JEDAccessDefinition(ResourceLocation rule, boolean respawn, boolean reconnect, boolean initialSpawn,
                                  String bypass, boolean nonPlayers, ResourceLocation fallback,
                                  String visibility, String feedback, JsonElement message) {
    public static final JEDAccessDefinition DEFAULT=new JEDAccessDefinition(null,false,false,false,"explicit",true,null,"locked","requirements",null);
    public JEDAccessDefinition {if(message!=null)message=message.deepCopy();}
    public static final Set<String> FIELDS=Set.of("rule","respawn","reconnect","initial_spawn","bypass","non_players","fallback","visibility","feedback","message");
    public static JEDAccessDefinition decode(JsonElement input,String at){
        var j=JEDJson.object(input,at);JEDJson.keys(j,FIELDS,at);
        String feedback=choice(j,"feedback","requirements",Set.of("requirements","generic","custom"),at);
        if(feedback.equals("custom")&&!j.has("message"))throw new JEDDefinitionException(at+": custom feedback requires message");
        return new JEDAccessDefinition(id(j,"rule",at),flag(j,"respawn",false,at),flag(j,"reconnect",false,at),flag(j,"initial_spawn",false,at),
            choice(j,"bypass","explicit",Set.of("explicit","operator","never"),at),flag(j,"non_players",true,at),id(j,"fallback",at),
            choice(j,"visibility","locked",Set.of("locked","hidden"),at),feedback,j.get("message"));
    }
    public static boolean flag(JsonObject j,String key,boolean fallback,String at){
        if(!j.has(key))return fallback;var v=j.get(key);
        if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(at+"."+key+": expected a boolean");
        return v.getAsBoolean();
    }
    public static String choice(JsonObject j,String key,String fallback,Set<String> values,String at){
        String v=j.has(key)?JEDJson.string(j.get(key),at+"."+key):fallback;
        if(!values.contains(v))throw new JEDDefinitionException(at+"."+key+": expected one of "+new TreeSet<>(values));return v;
    }
    private static ResourceLocation id(JsonObject j,String key,String at){return j.has(key)?JEDDimensionDefinition.id(JEDJson.string(j.get(key),at+"."+key),at):null;}
}
