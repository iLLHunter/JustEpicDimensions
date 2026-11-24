package justepicdimensions.time;

import com.google.gson.*;
import java.util.Set;
import justepicdimensions.definition.*;

/** Immutable per-level timing policy. Lengths and command values are raw game ticks. */
public record JEDTimeDefinition(int dayLength, int nightLength, String celestial,
                                AngleRange angleRange, ClockRange clockRange) {
    public record AngleRange(float min, float max) {}
    public record ClockRange(int min, int max, int calendarDays) {
        public long width() { return (long) max - min + 1; }
    }
    public long cycleLength() { return (long) dayLength + nightLength; }
    public boolean customAngle() { return !celestial.equals("native"); }

    public static JEDTimeDefinition decode(JsonElement value, String path) {
        var json=JEDJson.object(value,path);
        JEDJson.keys(json,Set.of("day_length","night_length","celestial","angle_range","clock_range"),path);
        int day=integer(json,"day_length",12000,path), night=integer(json,"night_length",12000,path);
        if(day<1 || night<1 || (long)day+night>Integer.MAX_VALUE)
            throw new JEDDefinitionException(path+": positive day/night lengths and a total at most 2147483647 are required");
        String mode=json.has("celestial")?JEDJson.string(json.get("celestial"),path+".celestial"):"native";
        if(!Set.of("native","cycle","range").contains(mode))throw new JEDDefinitionException(path+": celestial must be native, cycle or range");
        AngleRange angle=null;
        if(json.has("angle_range")) {
            var range=JEDJson.object(json.get("angle_range"),path+".angle_range");
            JEDJson.keys(range,Set.of("min","max"),path+".angle_range");
            angle=new AngleRange(angle(range.get("min"),path+".angle_range.min"),angle(range.get("max"),path+".angle_range.max"));
        }
        if(mode.equals("range") && angle==null)throw new JEDDefinitionException(path+": celestial range requires angle_range.min and max");
        ClockRange clock=null;
        if(json.has("clock_range")) {
            var range=JEDJson.object(json.get("clock_range"),path+".clock_range");
            JEDJson.keys(range,Set.of("min","max","calendar_days_per_repeat"),path+".clock_range");
            int min=JEDJson.integer(range.get("min"),path+".clock_range.min"),max=JEDJson.integer(range.get("max"),path+".clock_range.max");
            if(min<0 || max<min || max>=(long)day+night)throw new JEDDefinitionException(path+": clock range must satisfy 0 <= min <= max < day_length + night_length");
            clock=new ClockRange(min,max,integer(range,"calendar_days_per_repeat",1,path+".clock_range"));
        }
        return new JEDTimeDefinition(day,night,mode,angle,clock);
    }
    private static int integer(JsonObject json,String key,int fallback,String path) {return json.has(key)?JEDJson.integer(json.get(key),path+"."+key):fallback;}
    private static float angle(JsonElement value,String path) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new JEDDefinitionException(path+": expected a number from 0 to 1");
        float result=value.getAsFloat();
        if(!Float.isFinite(result) || result<0 || result>1)throw new JEDDefinitionException(path+": expected a number from 0 to 1");
        return result;
    }
    public JsonObject json() {
        var json=new JsonObject();json.addProperty("day_length",dayLength);json.addProperty("night_length",nightLength);json.addProperty("celestial",celestial);
        if(angleRange!=null){var range=new JsonObject();range.addProperty("min",angleRange.min);range.addProperty("max",angleRange.max);json.add("angle_range",range);}
        if(clockRange!=null){var range=new JsonObject();range.addProperty("min",clockRange.min);range.addProperty("max",clockRange.max);range.addProperty("calendar_days_per_repeat",clockRange.calendarDays);json.add("clock_range",range);}
        return json;
    }
}
