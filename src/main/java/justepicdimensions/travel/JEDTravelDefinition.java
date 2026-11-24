package justepicdimensions.travel;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Modern typed travel rules. Coordinates are evaluated once, before destination preparation. */
public record JEDTravelDefinition(boolean entry, boolean exit, int interval, int cooldown,
                                  String passengers, Rule sky, Rule below) {
    public static final JEDTravelDefinition DEFAULT = new JEDTravelDefinition(true, true, 40, 20, "dismount", null, null);
    public record Axis(double value, boolean relative) {
        double apply(double base) { return relative ? base + value : value; }
    }
    public record Position(Axis x, Axis y, Axis z) {
        public Vec3 apply(Vec3 base) { return new Vec3(x.apply(base.x), y.apply(base.y), z.apply(base.z)); }
    }
    public record Rule(ResourceLocation dimension, double triggerY, String mode, Position position, Vec3 scale,
                       String scaling, boolean surface, String surfaceFallback, String velocity,
                       boolean removeFallDamage, Float fallDistance, Float minimumHealth) {
        public Vec3 position(Vec3 original, Vec3 spawn, double dimensionScale) {
            Vec3 base = original;
            if (mode.equals("spawn")) return spawn;
            if (mode.equals("scaled") || mode.equals("scaled_relative"))
                base = scaling.equals("dimension") ? original.multiply(dimensionScale, 1, dimensionScale) : original.multiply(scale);
            return position == null ? base : position.apply(base);
        }
    }
    public static JEDTravelDefinition decode(JsonElement input, String at) {
        var object = JEDJson.object(input, at);
        JEDJson.keys(object, Set.of("allow_entry", "allow_exit", "check_interval", "cooldown", "passengers", "sky", "void"), at);
        return new JEDTravelDefinition(flag(object,"allow_entry",true,at), flag(object,"allow_exit",true,at),
            integer(object,"check_interval",40,1,12000,at), integer(object,"cooldown",20,0,12000,at),
            choice(object,"passengers","dismount",Set.of("dismount","together"),at),
            object.has("sky") ? rule(object.get("sky"),at+".sky") : null,
            object.has("void") ? rule(object.get("void"),at+".void") : null);
    }
    static Rule rule(JsonElement input, String at) {
        var object = JEDJson.object(input, at);
        JEDJson.keys(object, Set.of("dimension","trigger_y","mode","position","scale","scaling","surface","surface_fallback","velocity","remove_fall_damage","fall_distance","minimum_health"), at);
        var dimension = JEDDimensionDefinition.id(JEDJson.string(object.get("dimension"),at+".dimension"),at);
        double trigger = number(object.get("trigger_y"),at+".trigger_y");
        String mode = choice(object,"mode","same",Set.of("same","fixed","spawn","scaled","relative","scaled_relative"),at);
        boolean coordinates = Set.of("fixed","relative","scaled_relative").contains(mode);
        if (coordinates != object.has("position")) throw new JEDDefinitionException(at+": position is required only for fixed, relative and scaled_relative modes");
        Position position = coordinates ? position(object.get("position"), !mode.equals("fixed"),at+".position") : null;
        boolean scaled = mode.equals("scaled") || mode.equals("scaled_relative");
        if (!scaled && (object.has("scale") || object.has("scaling"))) throw new JEDDefinitionException(at+": scaling requires a scaled mode");
        String scaling = choice(object,"scaling","custom",Set.of("custom","dimension"),at);
        if (scaling.equals("dimension") && object.has("scale")) throw new JEDDefinitionException(at+": choose dimension scaling or a custom scale, not both");
        Vec3 scale = object.has("scale") ? position(object.get("scale"),false,at+".scale").apply(Vec3.ZERO) : new Vec3(1,1,1);
        return new Rule(dimension,trigger,mode,position,scale,scaling,flag(object,"surface",false,at),
            choice(object,"surface_fallback","exact",Set.of("exact","spawn","cancel"),at),
            choice(object,"velocity","preserve",Set.of("preserve","reset"),at),flag(object,"remove_fall_damage",false,at),
            nonnegative(object,"fall_distance",at),nonnegative(object,"minimum_health",at));
    }
    public static Position position(JsonElement input, boolean relative, String at) {
        if (input == null || !input.isJsonArray() || input.getAsJsonArray().size()!=3) throw new JEDDefinitionException(at+": expected three coordinates");
        var values = input.getAsJsonArray();
        return new Position(axis(values.get(0),relative,at),axis(values.get(1),relative,at),axis(values.get(2),relative,at));
    }
    private static Axis axis(JsonElement input, boolean relative, String at) {
        if (relative && input.isJsonPrimitive() && input.getAsJsonPrimitive().isString()) {
            String value=input.getAsString();
            if (value.startsWith("~")) {
                try { double offset=value.length()==1?0:Double.parseDouble(value.substring(1)); if(Double.isFinite(offset))return new Axis(offset,true); }
                catch(NumberFormatException ignored) {}
            }
            throw new JEDDefinitionException(at+": use a number or a relative coordinate such as ~10");
        }
        return new Axis(number(input,at),false);
    }
    private static Float nonnegative(JsonObject object,String key,String at) {
        if(!object.has(key))return null;
        double value=number(object.get(key),at+"."+key);
        if(value<0 || value>Float.MAX_VALUE)throw new JEDDefinitionException(at+"."+key+": expected a nonnegative float");
        return (float)value;
    }
    private static double number(JsonElement input,String at) {
        if(input==null||!input.isJsonPrimitive()||!input.getAsJsonPrimitive().isNumber()||!Double.isFinite(input.getAsDouble()))throw new JEDDefinitionException(at+": expected a finite number");
        return input.getAsDouble();
    }
    private static boolean flag(JsonObject object,String key,boolean fallback,String at) {
        if(!object.has(key))return fallback;
        var input=object.get(key);
        if(!input.isJsonPrimitive()||!input.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(at+"."+key+": expected a boolean");
        return input.getAsBoolean();
    }
    private static String choice(JsonObject object,String key,String fallback,Set<String> choices,String at) {
        String value=object.has(key)?JEDJson.string(object.get(key),at+"."+key):fallback;
        if(!choices.contains(value))throw new JEDDefinitionException(at+"."+key+": expected one of "+new TreeSet<>(choices));
        return value;
    }
    private static int integer(JsonObject object,String key,int fallback,int min,int max,String at) {
        int value=object.has(key)?JEDJson.integer(object.get(key),at+"."+key):fallback;
        if(value<min||value>max)throw new JEDDefinitionException(at+"."+key+": expected "+min+".."+max);
        return value;
    }
    public static void reset(String path,String at) {
        if(path.equals("travel"))return;
        String key=path.substring("travel.".length());
        if(Set.of("allow_entry","allow_exit","check_interval","cooldown","passengers","sky","void").contains(key))return;
        if((key.startsWith("sky.")||key.startsWith("void.")) && Set.of("dimension","trigger_y","mode","position","scale","scaling","surface","surface_fallback","velocity","remove_fall_damage","fall_distance","minimum_health").contains(key.substring(key.indexOf('.')+1)))return;
        throw new JEDDefinitionException(at+": unknown travel reset property "+path);
    }
}
