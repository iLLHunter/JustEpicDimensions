package justepicdimensions.world;

import com.google.gson.*;
import com.mojang.brigadier.StringReader;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.GameRules;

/** Modern authored settings, converted once to a validated patch of native state. */
public record JEDStateDefinition(Values initial,Values onLoad,boolean followDimensionHardcoreRules) {
    public static JEDStateDefinition decode(JsonElement value,String source){
        var root=JEDJson.object(value,source);JEDJson.keys(root,Set.of("initial","on_load","follow_dimension_hardcore_rules"),source);
        return new JEDStateDefinition(Values.decode(root.has("initial")?root.get("initial"):new JsonObject(),source+".initial"),Values.decode(root.has("on_load")?root.get("on_load"):new JsonObject(),source+".on_load"),!root.has("follow_dimension_hardcore_rules")||Values.bool(root.get("follow_dimension_hardcore_rules"),source+".follow_dimension_hardcore_rules"));
    }
    public static final class Values {
        private final CompoundTag patch;
        private Values(CompoundTag patch){this.patch=patch;}
        public void apply(JEDLevelData data){data.applyPatch(patch);}
        static Values decode(JsonElement value,String source){
            var json=JEDJson.object(value,source);var tag=new CompoundTag();
            JEDJson.keys(json,Set.of("game_time","day_time","day_time_fraction","day_time_per_tick","level_name","default_game_mode","difficulty","difficulty_locked","hardcore","allow_commands","initialized","spawn","weather","gamerules","border"),source);
            for(var entry:json.entrySet()){
                String key=entry.getKey();var item=entry.getValue();String path=source+"."+key;
                switch(key){
                    case "game_time","day_time" -> tag.putLong(key.equals("game_time")?"Time":"DayTime",integer64(item,path));
                    case "day_time_fraction","day_time_per_tick" -> tag.putFloat(key.equals("day_time_fraction")?"neoDayTimeFraction":"neoDayTimePerTick",floatNumber(item,path));
                    case "level_name" -> tag.putString("LevelName",JEDJson.string(item,path));
                    case "default_game_mode" -> {String mode=JEDJson.string(item,path);int id=switch(mode){case "survival"->0;case "creative"->1;case "adventure"->2;case "spectator"->3;default->throw new JEDDefinitionException(path+": unknown game mode "+mode);};tag.putInt("GameType",id);}
                    case "difficulty" -> {String difficulty=JEDJson.string(item,path);int id=switch(difficulty){case "peaceful"->0;case "easy"->1;case "normal"->2;case "hard"->3;default->throw new JEDDefinitionException(path+": unknown difficulty "+difficulty);};tag.putByte("Difficulty",(byte)id);}
                    case "difficulty_locked","hardcore","allow_commands","initialized" -> tag.putBoolean(switch(key){case "difficulty_locked"->"DifficultyLocked";case "allow_commands"->"allowCommands";default->key;},bool(item,path));
                    case "spawn" -> {var spawn=JEDJson.object(item,path);JEDJson.keys(spawn,Set.of("x","y","z","angle"),path);for(var part:spawn.entrySet())if(part.getKey().equals("angle"))tag.putFloat("SpawnAngle",floatNumber(part.getValue(),path+".angle"));else tag.putInt("Spawn"+part.getKey().toUpperCase(Locale.ROOT),JEDJson.integer(part.getValue(),path));}
                    case "weather" -> {var weather=JEDJson.object(item,path);JEDJson.keys(weather,Set.of("raining","thundering","clear_time","rain_time","thunder_time"),path);for(var part:weather.entrySet())switch(part.getKey()){case "raining","thundering"->tag.putBoolean(part.getKey(),bool(part.getValue(),path));default->tag.putInt(switch(part.getKey()){case "clear_time"->"clearWeatherTime";case "rain_time"->"rainTime";default->"thunderTime";},JEDJson.integer(part.getValue(),path));}}
                    case "gamerules" -> {var rules=JEDJson.object(item,path);var output=new CompoundTag();var found=new HashSet<String>();GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor(){public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key,GameRules.Type<T> type){if(!rules.has(key.getId()))return;var rule=rules.get(key.getId());if(!rule.isJsonPrimitive()||(!rule.getAsJsonPrimitive().isBoolean()&&!rule.getAsJsonPrimitive().isNumber()))throw new JEDDefinitionException(path+"."+key.getId()+": expected native boolean/integer value");String encoded=rule.toString();try{var reader=new StringReader(encoded);type.createArgument("value").getType().parse(reader);if(reader.canRead())throw new IllegalArgumentException();}catch(Exception error){throw new JEDDefinitionException(path+"."+key.getId()+": invalid native value "+encoded);}output.putString(key.getId(),encoded);found.add(key.getId());}});for(String name:rules.keySet())if(!found.contains(name))throw new JEDDefinitionException(path+": unknown native gamerule "+name);tag.put("GameRules",output);}
                    case "border" -> {var border=JEDJson.object(item,path);var names=Map.of("center_x","BorderCenterX","center_z","BorderCenterZ","size","BorderSize","lerp_time","BorderSizeLerpTime","lerp_target","BorderSizeLerpTarget","safe_zone","BorderSafeZone","damage_per_block","BorderDamagePerBlock","warning_blocks","BorderWarningBlocks","warning_time","BorderWarningTime");JEDJson.keys(border,names.keySet(),path);for(var part:border.entrySet()){String name=part.getKey();if(name.equals("lerp_time")){tag.putLong(names.get(name),integer64(part.getValue(),path+".lerp_time"));}else if(name.startsWith("warning_"))tag.putInt(names.get(name),JEDJson.integer(part.getValue(),path));else tag.putDouble(names.get(name),number(part.getValue(),path).doubleValue());}}
                }
            }
            return new Values(tag);
        }
        static Number number(JsonElement value,String path){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!Double.isFinite(value.getAsDouble()))throw new JEDDefinitionException(path+": expected a finite number");return value.getAsNumber();}
        static float floatNumber(JsonElement value,String path){float result=number(value,path).floatValue();if(!Float.isFinite(result))throw new JEDDefinitionException(path+": float overflow");return result;}
        static long integer64(JsonElement value,String path){try{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();return value.getAsBigDecimal().longValueExact();}catch(Exception error){throw new JEDDefinitionException(path+": expected an exact 64-bit integer");}}
        static boolean bool(JsonElement value,String path){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(path+": expected boolean");return value.getAsBoolean();}
    }
}
