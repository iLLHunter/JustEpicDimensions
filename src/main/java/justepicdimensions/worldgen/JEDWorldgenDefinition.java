package justepicdimensions.worldgen;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

/** Construction-time settings; native generator/biome/type JSON remains authoritative. */
public record JEDWorldgenDefinition(String seed, Boolean structures, java.util.List<net.minecraft.resources.ResourceLocation> structureSets) {
    public JEDWorldgenDefinition(String seed,Boolean structures){this(seed,structures,null);}
    public JEDWorldgenDefinition {if(structureSets!=null)structureSets=java.util.List.copyOf(structureSets);}
    public static final JEDWorldgenDefinition DEFAULT = new JEDWorldgenDefinition(null, null, null);
    public static JEDWorldgenDefinition decode(JsonElement value, String source) {
        var json = JEDJson.object(value, source);
        JEDJson.keys(json, Set.of("seed", "structures", "structure_sets"), source);
        String seed = null;
        if (json.has("seed")) {
            var v = json.get("seed");
            if (!v.isJsonPrimitive()) throw new JEDDefinitionException(source + ".seed: expected an integer or nonempty seed string");
            var p = v.getAsJsonPrimitive();
            if (p.isNumber()) {
                try { seed = Long.toString(p.getAsBigDecimal().longValueExact()); }
                catch (ArithmeticException e) { throw new JEDDefinitionException(source + ".seed: expected a signed 64-bit integer"); }
            } else if (p.isString() && !p.getAsString().isBlank()) seed = p.getAsString();
            else throw new JEDDefinitionException(source + ".seed: expected an integer or nonempty seed string");
        }
        Boolean structures = null;
        if (json.has("structures")) {
            var v = json.get("structures");
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) throw new JEDDefinitionException(source + ".structures: expected a boolean");
            structures = v.getAsBoolean();
        }
        java.util.List<net.minecraft.resources.ResourceLocation> sets=null;
        if(json.has("structure_sets")){if(!json.get("structure_sets").isJsonArray())throw new JEDDefinitionException(source+".structure_sets: expected an array");var ids=new java.util.LinkedHashSet<net.minecraft.resources.ResourceLocation>();for(var element:json.getAsJsonArray("structure_sets")){var id=JEDDimensionDefinition.id(JEDJson.string(element,source+".structure_sets"),source);if(!ids.add(id))throw new JEDDefinitionException(source+": duplicate structure set "+id);}sets=java.util.List.copyOf(ids);}
        return new JEDWorldgenDefinition(seed, structures, sets);
    }
}
