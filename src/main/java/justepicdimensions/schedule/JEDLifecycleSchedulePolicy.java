package justepicdimensions.schedule;

import com.google.gson.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Reusable immutable schedule definitions; mutable progress belongs to each target dimension. */
public record JEDLifecycleSchedulePolicy(ResourceLocation id,List<Rule> rules){
    public static final String DIRECTORY="justepicdimensions/lifecycle_schedules";
    public enum Clock { CALENDAR, REAL_TIME, SERVER_TICKS }
    public enum Action { RESET, AVAILABILITY }
    public record Warnings(long seconds,boolean before,String audience){public long units(Clock clock){return Math.multiplyExact(seconds,clock==Clock.SERVER_TICKS?20:1000);}}
    public record Retention(String mode,long value){}
    public record Rule(String id,Action action,Clock clock,long interval,JEDScheduleCalendar calendar,LocalTime closeAt,long openFor,
            boolean initiallyOpen,String occupied,String arrivals,String missed,String cadence,Warnings warnings,Retention retention,
            boolean relocate,String onTimingChange,String signature){
        public String key(ResourceLocation policy){return policy+"/"+id;}
        public long next(long after){if(clock!=Clock.CALENDAR)return Math.addExact(after,interval);var next=calendar.next(Instant.ofEpochMilli(after));return next==null?Long.MAX_VALUE:next.toEpochMilli();}
        public long closes(long opened){
            if(closeAt==null)return Math.addExact(opened,openFor);
            var local=Instant.ofEpochMilli(opened).atZone(calendar.zone());var date=local.toLocalDate();if(!closeAt.isAfter(local.toLocalTime()))date=date.plusDays(1);
            for(int day=0;day<3;day++,date=date.plusDays(1))for(var candidate:calendar.resolve(date.atTime(closeAt)))if(candidate.toEpochMilli()>opened)return candidate.toEpochMilli();
            throw new JEDDefinitionException("Calendar window has no valid closing time after "+local);
        }
    }
    public static JEDLifecycleSchedulePolicy decode(ResourceLocation id,JEDSourceDocument document){
        var root=JEDJson.object(document.value(),id.toString());JEDJson.keys(root,Set.of("format_version","rules"),id.toString());if(JEDJson.integer(root.get("format_version"),id+".format_version")!=1)throw new JEDDefinitionException(id+": expected schedule format_version 1");
        var rules=new ArrayList<Rule>();var ids=new HashSet<String>();int index=0;
        for(var raw:array(root,"rules",id.toString())){
            String at=id+".rules["+index+++"]";var json=JEDJson.object(raw,at);JEDJson.keys(json,Set.of("id","action","clock","calendar","zone","gap","overlap","interval_seconds","interval_ticks","close_at","open_for_seconds","open_for_ticks","initial_open","occupied","arrivals","missed","cadence","warnings","retention","relocate","on_timing_change"),at);
            String name=JEDJson.string(json.get("id"),at+".id");if(!name.matches("[a-z0-9_./-]+")||!ids.add(name))throw new JEDDefinitionException(at+": rule IDs must be unique lowercase resource paths");
            var action=Action.valueOf(choice(json,"action",null,Set.of("reset","availability"),at).toUpperCase(Locale.ROOT));var clock=Clock.valueOf(choice(json,"clock",null,Set.of("calendar","real_time","server_ticks"),at).toUpperCase(Locale.ROOT));
            JEDScheduleCalendar calendar=null;long interval=0,openFor=0;LocalTime closeAt=null;boolean initiallyOpen=true;
            if(clock==Clock.CALENDAR){
                var zone=zone(string(json,"zone","UTC",at),at);var gap=JEDScheduleCalendar.Gap.valueOf(choice(json,"gap","first_valid",Set.of("first_valid","shift","skip"),at).toUpperCase(Locale.ROOT));var overlap=JEDScheduleCalendar.Overlap.valueOf(choice(json,"overlap","first",Set.of("first","second","both"),at).toUpperCase(Locale.ROOT));
                calendar=JEDScheduleCalendar.decode(json.get("calendar"),zone,gap,overlap,at+".calendar");reject(json,at,"interval_seconds","interval_ticks","initial_open","open_for_ticks");
            }else{
                String field=clock==Clock.SERVER_TICKS?"interval_ticks":"interval_seconds";long units=positive(json.get(field),at+"."+field);interval=clock==Clock.SERVER_TICKS?units:scale(units,1000,at);
                reject(json,at,"calendar","zone","gap","overlap","close_at",clock==Clock.SERVER_TICKS?"interval_seconds":"interval_ticks",clock==Clock.SERVER_TICKS?"open_for_seconds":"open_for_ticks");
            }
            boolean reset=action==Action.RESET;
            if(!reset){
                if(clock==Clock.CALENDAR&&json.has("close_at")){closeAt=time(json.get("close_at"),at+".close_at");reject(json,at,"open_for_seconds");}
                else{String field=clock==Clock.SERVER_TICKS?"open_for_ticks":"open_for_seconds";long units=positive(json.get(field),at+"."+field);openFor=clock==Clock.SERVER_TICKS?units:scale(units,1000,at);if(clock!=Clock.CALENDAR&&openFor>interval)throw new JEDDefinitionException(at+": open duration must not exceed the interval");}
                if(clock!=Clock.CALENDAR&&json.has("initial_open"))initiallyOpen=flag(json,"initial_open",at);
                reject(json,at,"relocate","retention","missed","cadence");
            }else reject(json,at,"close_at","open_for_seconds","open_for_ticks","initial_open");
            String occupied=choice(json,"occupied",reset?"defer":"stay",reset?Set.of("defer","evacuate","skip"):Set.of("stay","evacuate"),at);
            String arrivals=choice(json,"arrivals","ordinary",reset?Set.of("ordinary","all","open"):Set.of("ordinary","all"),at);
            String missed=choice(json,"missed","run_once",Set.of("run_once","skip"),at);String cadence=choice(json,"cadence","completion",Set.of("completion","scheduled"),at);if(clock==Clock.CALENDAR&&json.has("cadence"))throw new JEDDefinitionException(at+": calendar schedules retain their calendar cadence");
            var warnings=warnings(json.get("warnings"),at+".warnings");var retention=retention(json.get("retention"),at+".retention");boolean relocate=!json.has("relocate")||flag(json,"relocate",at);
            rules.add(new Rule(name,action,clock,interval,calendar,closeAt,openFor,initiallyOpen,occupied,arrivals,missed,cadence,warnings,retention,relocate,choice(json,"on_timing_change","restart",Set.of("restart","recompute"),at),signature(new JsonPrimitive(action+"|"+clock+"|"+interval+"|"+(calendar==null?"":calendar.signature())+"|"+closeAt+"|"+openFor+"|"+initiallyOpen))));
        }return new JEDLifecycleSchedulePolicy(id,List.copyOf(rules));
    }
    private static Warnings warnings(JsonElement raw,String at){var json=raw==null?new JsonObject():JEDJson.object(raw,at);JEDJson.keys(json,Set.of("grace_seconds","timing","audience"),at);long seconds=json.has("grace_seconds")?nonnegative(json.get("grace_seconds"),at+".grace_seconds"):600;scale(seconds,1000,at);return new Warnings(seconds,choice(json,"timing","after",Set.of("before","after"),at).equals("before"),choice(json,"audience","affected_operators",Set.of("affected_operators","affected","all"),at));}
    private static Retention retention(JsonElement raw,String at){var json=raw==null?new JsonObject():JEDJson.object(raw,at);JEDJson.keys(json,Set.of("mode","days","count"),at);String mode=choice(json,"mode","age",Set.of("age","count","unlimited"),at);if(mode.equals("unlimited")){reject(json,at,"days","count");return new Retention(mode,0);}if(mode.equals("count")){reject(json,at,"days");return new Retention(mode,positive(json.get("count"),at+".count"));}reject(json,at,"count");long days=json.has("days")?positive(json.get("days"),at+".days"):7;scale(days,86400000,at);return new Retention(mode,days);}
    static long nonnegative(JsonElement raw,String at){try{if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long value=raw.getAsBigDecimal().longValueExact();if(value<0)throw new ArithmeticException();return value;}catch(ArithmeticException error){throw new JEDDefinitionException(at+": expected a nonnegative whole number",error);}}
    static long positive(JsonElement raw,String at){long value=nonnegative(raw,at);if(value==0)throw new JEDDefinitionException(at+": expected a positive whole number");return value;}
    private static long scale(long value,long multiplier,String at){try{return Math.multiplyExact(value,multiplier);}catch(ArithmeticException error){throw new JEDDefinitionException(at+": duration exceeds the supported clock range",error);}}
    static boolean flag(JsonObject json,String key,String at){var raw=json.get(key);if(raw==null||!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(at+"."+key+": expected a boolean");return raw.getAsBoolean();}
    static String string(JsonObject json,String key,String fallback,String at){return json.has(key)?JEDJson.string(json.get(key),at+"."+key):fallback;}
    static String choice(JsonObject json,String key,String fallback,Set<String> choices,String at){String value=string(json,key,fallback,at);if(value==null||!choices.contains(value))throw new JEDDefinitionException(at+"."+key+": choose "+new TreeSet<>(choices));return value;}
    private static JsonArray array(JsonObject json,String key,String at){if(!json.has(key))return new JsonArray();var raw=json.get(key);if(!raw.isJsonArray())throw new JEDDefinitionException(at+"."+key+": expected an array");return raw.getAsJsonArray();}
    private static void reject(JsonObject json,String at,String... keys){for(String key:keys)if(json.has(key))throw new JEDDefinitionException(at+"."+key+": does not apply to this schedule type");}
    private static ZoneId zone(String raw,String at){try{return ZoneId.of(raw);}catch(DateTimeException error){throw new JEDDefinitionException(at+": unknown time zone "+raw,error);}}
    private static LocalTime time(JsonElement raw,String at){try{var value=LocalTime.parse(JEDJson.string(raw,at));if(value.getNano()!=0)throw new DateTimeException("Use whole seconds");return value;}catch(DateTimeException error){throw new JEDDefinitionException(at+": expected HH:mm or HH:mm:ss",error);}}
    private static String signature(JsonElement json){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JEDJson.canonical(json).toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
