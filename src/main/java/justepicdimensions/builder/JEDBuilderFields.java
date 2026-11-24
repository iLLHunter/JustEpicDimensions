package justepicdimensions.builder;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.JEDText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameRules;

/** Presentation metadata only. The server's existing codecs remain authoritative. */
public final class JEDBuilderFields {
    public enum Kind { BOOLEAN, INTEGER, NUMBER, TEXT, RESOURCE, COLOR, JSON, CHOICE, SEED }
    public record Field(String document,String path,Kind kind,List<String> choices,String registry,String help) {
        public String category(){return document.equals("dimension")?"Native":path.contains(".")?path.substring(0,path.indexOf('.')):"General";}
        public String label(){return JEDBuilderFieldInfo.label(this).getString();}
        public JsonElement parse(String text){
            JsonElement value;
            if(kind==Kind.TEXT||kind==Kind.RESOURCE||kind==Kind.COLOR||kind==Kind.CHOICE)value=new JsonPrimitive(text);
            else if(kind==Kind.SEED){
                if(text.startsWith("\""))value=json(text);
                else if(text.strip().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))value=json(text);
                else value=new JsonPrimitive(text);
            }else value=json(text);
            validate(value);return value;
        }
        public void validate(JsonElement value){
            try{
                var primitive=value!=null&&value.isJsonPrimitive()?value.getAsJsonPrimitive():null;
                boolean string=primitive!=null&&primitive.isString(),numeric=primitive!=null&&primitive.isNumber();
                switch(kind){
                    case TEXT -> {if(!string)throw expected();if(document.equals("properties")&&path.equals("display_name")&&(value.getAsString().isBlank()||value.getAsString().length()>256))throw JEDText.failure("justepicdimensions.builder.name_error");}
                    case RESOURCE -> {if(!string)throw expected();try{ResourceLocation.parse(value.getAsString());}catch(Exception e){throw JEDText.failure("justepicdimensions.builder.resource_error");}}
                    case CHOICE -> {if(!string||!choices.contains(value.getAsString()))throw JEDText.failure("input.choose",choices);}
                    case COLOR -> {if(!string||!value.getAsString().matches("#[0-9a-fA-F]{6}"))throw JEDText.failure("justepicdimensions.builder.color_error");}
                    case BOOLEAN -> {if(primitive==null||!primitive.isBoolean())throw expected();}
                    case INTEGER,NUMBER -> {
                        if(!numeric||!Double.isFinite(value.getAsDouble()))throw expected();
                        if(kind==Kind.INTEGER){if(path.endsWith(".game_time")||path.endsWith(".day_time")||path.endsWith(".lerp_time"))value.getAsBigDecimal().longValueExact();else value.getAsBigDecimal().intValueExact();}
                        var range=JEDBuilderFieldInfo.bounds(this);if(range!=null&&(value.getAsDouble()<range[0]||value.getAsDouble()>range[1]))throw JEDText.failure("justepicdimensions.builder.out_of_range",range[0],range[1]);
                    }
                    case SEED -> {if(numeric)value.getAsBigDecimal().longValueExact();else if(!string)throw expected();}
                    case JSON -> {
                        if(path.equals("profiles")){var document=new JsonObject();document.add("profiles",value);justepicdimensions.profile.JEDSettingProfiles.references(document,"profile list");}
                        String shape=JEDBuilderFieldInfo.jsonType(this);
                        if(shape.equals("rules"))biomeRules(value);
                        else if(shape.equals("brightness")){
                            if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=16)throw expected();
                            for(var item:value.getAsJsonArray())if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isNumber()||!Float.isFinite(item.getAsFloat()))throw expected();
                        }else if(shape.equals("ids")||shape.equals("commands")){
                            if(value==null||!value.isJsonArray())throw expected();
                            for(var item:value.getAsJsonArray()){if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())throw expected();if(shape.equals("ids"))try{ResourceLocation.parse(item.getAsString());}catch(Exception e){throw JEDText.failure("justepicdimensions.builder.resource_error");}}
                        }else if(shape.equals("offset")){
                            if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=3)throw expected();
                            for(var item:value.getAsJsonArray()){if(!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isNumber())throw expected();item.getAsBigDecimal().intValueExact();}
                        }else if(shape.equals("vector")){
                            try{justepicdimensions.travel.JEDTravelDefinition.position(value,path.endsWith(".position"),path);}catch(JEDDefinitionException error){throw expected();}
                        }else if(!shape.equals("any_json")&&(value==null||!value.isJsonObject()))throw expected();
                    }
                }
            }catch(ArithmeticException error){throw JEDText.failure("justepicdimensions.builder.integer_error");}
        }
        private JEDDefinitionException expected(){return JEDText.failure("justepicdimensions.builder.expected",JEDBuilderFieldInfo.accepted(this));}
        public String text(JsonElement value){return value==null?"":switch(kind){case SEED->value.toString();case TEXT,RESOURCE,CHOICE->value.isJsonPrimitive()?value.getAsString():value.toString();case COLOR->value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():value.toString();default->value.toString();};}
        public boolean resettable(){if(!document.equals("properties"))return false;try{JEDDimensionDefinition.validateReset(path,"GUI");return true;}catch(Exception e){return false;}}
    }
    /** Preserve strict JSON while keeping parser implementation details out of the editor. */
    public static JsonElement json(String text){
        try{return JEDBuilderWire.value(text);}catch(Exception error){
            String message=String.valueOf(error.getMessage());
            if(message.contains("duplicate key"))throw JEDText.failure("justepicdimensions.builder.duplicate_json",message.substring(message.indexOf("duplicate key")+14));
            var location=java.util.regex.Pattern.compile("line [0-9]+ column [0-9]+(?: path [^\\s]+)?").matcher(message);
            throw JEDText.failure("justepicdimensions.builder.invalid_json",location.find()?location.group():"");
        }
    }
    public static void biomeRules(JsonElement value){
        if(value==null||!value.isJsonArray())throw JEDText.failure("justepicdimensions.builder.expected",JEDBuilderFieldInfo.tr("type.rules"));
        int index=0;for(var item:value.getAsJsonArray()){
            index++;if(!item.isJsonObject())throw JEDText.failure("justepicdimensions.builder.rule_object",index);
            var row=item.getAsJsonObject();for(String key:row.keySet())if(!Set.of("biome","tag","regex","color").contains(key))throw JEDText.failure("justepicdimensions.builder.rule_unknown",index,key);
            if((row.has("biome")?1:0)+(row.has("tag")?1:0)+(row.has("regex")?1:0)!=1)throw JEDText.failure("justepicdimensions.builder.rule_selector",index);
            var color=row.get("color");if(color==null||!color.isJsonPrimitive()||!color.getAsJsonPrimitive().isString()||!color.getAsString().matches("#[0-9a-fA-F]{6}"))throw JEDText.failure("justepicdimensions.builder.rule_color",index);
            String kind=row.has("biome")?"biome":row.has("tag")?"tag":"regex";var selector=row.get(kind);
            if(!selector.isJsonPrimitive()||!selector.getAsJsonPrimitive().isString())throw JEDText.failure("justepicdimensions.builder.rule_selector",index);
            if(kind.equals("regex"))try{if(selector.getAsString().length()>1024)throw new IllegalArgumentException();java.util.regex.Pattern.compile(selector.getAsString());}catch(Exception error){throw JEDText.failure("justepicdimensions.builder.rule_pattern",index);}
            else try{if(selector.getAsString().isBlank()||ResourceLocation.parse(selector.getAsString()).getPath().isEmpty())throw new IllegalArgumentException();}catch(Exception error){throw JEDText.failure("justepicdimensions.builder.rule_id",index);}
        }
    }
    private static final List<Field> FIELDS=build();
    public static List<Field> all(){return FIELDS;}
    private static List<Field> build(){var a=new ArrayList<Field>();
        a.add(new Field("dimension","type",Kind.RESOURCE,List.of(),"minecraft:dimension_type","Native dimension type. Changing an existing native definition takes effect after restart."));
        a.add(new Field("dimension","generator",Kind.JSON,List.of(),"","Native generator object, including addon codecs. References stay shared when copying dimensions."));
        text(a,"display_name player.inventory_group lifecycle.template");json(a,"profiles");
        resource(a,"access.rule access.fallback mobs.policy environment.policy lifecycle.schedule pregeneration.policy instances.policy monitoring.policy");bool(a,"monitoring.enabled");bool(a,"access.respawn access.reconnect access.initial_spawn access.non_players");choice(a,"access.bypass","explicit operator never");choice(a,"access.visibility","locked hidden");choice(a,"access.feedback","requirements generic custom");json(a,"access.message");add(a,"generation.seed",Kind.SEED);json(a,"generation.structure_sets");
        for(var category:justepicdimensions.player.JEDPlayerCategory.values())resource(a,"player.groups."+category.key());
        bool(a,"spawn_chunks.enabled");integer(a,"spawn_chunks.radius");choice(a,"spawn_chunks.activity","native continuous");
        resource(a,"provider.type respawn.destination lifecycle.fallback travel.sky.dimension travel.void.dimension spawn_structure.asset");
        bool(a,"lifecycle.enabled lifecycle.load_on_start lifecycle.keep_loaded lifecycle.automatic_unload lifecycle.temporary state.follow_dimension_hardcore_rules weather.natural_lightning weather.precipitation_block_effects weather.preserve_on_sleep mobs.hostile mobs.peaceful spawn.ignore_suitability spawn.fallback_block render.sky.stars sleep.bed_checkpoints respawn.allow_here player.remove_global_inventory_items player.force_game_mode generation.structures border.center_on_spawn end.dragon_fight end.generate_spikes end.reconstruct_spikes provider.settings.surface_world provider.settings.water_vaporizes provider.settings.fog provider.settings.listed_have_fog travel.allow_entry travel.allow_exit spawn_structure.centered spawn_structure.paste_biomes render.sky.fixed render.sky.sunrise render.clouds.visible render.clouds.fixed render.fog.fixed render.fog.override_fluids render.fog.override_effects render.fog.override_boss render.weather.visible render.music.replace render.music.respect_special");
        integer(a,"lifecycle.idle_seconds time.day_length time.night_length time.clock_range.min time.clock_range.max time.clock_range.calendar_days_per_repeat spawn.x spawn.y spawn.z spawn.min_y spawn.max_y spawn.y_offset provider.settings.spawn_height travel.check_interval travel.cooldown spawn_structure.load_around render.music.min_delay render.music.max_delay");
        bool(a,"spawn.reset_velocity spawn.reset_fall_distance");
        integer(a,"mobs.creature_cap");
        number(a,"time.angle_range.min time.angle_range.max spawn.angle provider.settings.horizon render.sky.biome_blend render.sky.light_blend render.sky.brightness render.sky.brightness_factor render.clouds.height render.fog.near render.fog.far");
        json(a,"lighting.brightness_table lifecycle.post_delete_commands provider.settings.foggy_biomes provider.settings spawn_structure.offset render.biome_colors.grass render.biome_colors.foliage render.biome_colors.water");
        choice(a,"lighting.client_light_check","off updates chunks_and_updates");choice(a,"time.celestial","native cycle range");
        for(String p:List.of("weather.snow","weather.ice"))choice(a,p,"native disabled ignore_temperature");
        choice(a,"spawn.mode","native fixed surface cavern none");choice(a,"sleep.mode","native allow deny explode");
        choice(a,"respawn.priority","destination_first saved_point_first");choice(a,"border.centering","on_load follow_spawn");choice(a,"travel.passengers","dismount together");
        choice(a,"spawn_structure.rotation","none clockwise_90 clockwise_180 counterclockwise_90");choice(a,"spawn_structure.mirror","none left_right front_back");
        choice(a,"render.sky.mode","inherit overworld end none");choice(a,"render.fog.shape","sphere cylinder");choice(a,"render.music.mode","native silent preset sound");choice(a,"render.music.preset","game creative nether end end_boss under_water menu credits");resource(a,"render.music.sound");
        for(String p:List.of("render.sky.color","render.clouds.color","render.fog.color"))add(a,p,Kind.COLOR);
        for(String body:List.of("sun","moon")){String p="render.sky."+body+".";bool(a,p+"visible");number(a,p+"scale");resource(a,p+"texture");add(a,p+"color",Kind.COLOR);}
        for(String channel:List.of("sky","clouds","weather")){resource(a,"render."+channel+".renderer.id");json(a,"render."+channel+".renderer.settings");}
        for(String trigger:List.of("first_entry","respawn","every_entry")){String p="player.loadouts."+trigger+".";resource(a,p+"kit");choice(a,p+"mode","fill_empty top_up replace_slots replace_containers");}
        choice(a,"player.loadouts.first_entry.scope","group dimension");choice(a,"player.loadouts.respawn.keep_inventory","skip apply");choice(a,"player.loadouts.respawn.grave_policy","skip grant");choice(a,"player.loadouts.respawn.grave_scope","inventory_group death");choice(a,"player.loadouts.respawn.unknown_grave","skip grant");
        for(String channel:List.of("sky","void")){String p="travel."+channel+".";number(a,p+"trigger_y "+p+"fall_distance "+p+"minimum_health");bool(a,p+"surface "+p+"remove_fall_damage");json(a,p+"position "+p+"scale");choice(a,p+"mode","same fixed spawn scaled relative scaled_relative");choice(a,p+"scaling","custom dimension");choice(a,p+"surface_fallback","exact spawn cancel");choice(a,p+"velocity","preserve reset");}
        for(String phase:List.of("initial","on_load")){String p="state."+phase+".";
            text(a,p+"level_name");integer(a,p+"game_time "+p+"day_time "+p+"spawn.x "+p+"spawn.y "+p+"spawn.z "+p+"weather.clear_time "+p+"weather.rain_time "+p+"weather.thunder_time "+p+"border.lerp_time "+p+"border.warning_blocks "+p+"border.warning_time");
            number(a,p+"day_time_fraction "+p+"day_time_per_tick "+p+"spawn.angle "+p+"border.center_x "+p+"border.center_z "+p+"border.size "+p+"border.lerp_target "+p+"border.safe_zone "+p+"border.damage_per_block");
            bool(a,p+"difficulty_locked "+p+"hardcore "+p+"allow_commands "+p+"initialized "+p+"weather.raining "+p+"weather.thundering");choice(a,p+"default_game_mode","survival creative adventure spectator");choice(a,p+"difficulty","peaceful easy normal hard");json(a,p+"gamerules");
            GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor(){public void visitBoolean(GameRules.Key<GameRules.BooleanValue> key,GameRules.Type<GameRules.BooleanValue> type){bool(a,p+"gamerules."+key.getId());}public void visitInteger(GameRules.Key<GameRules.IntegerValue> key,GameRules.Type<GameRules.IntegerValue> type){integer(a,p+"gamerules."+key.getId());}});
        }
        for(int i=0;i<a.size();i++){var field=a.get(i);if(field.path().equals("spawn_chunks.radius")||field.path().endsWith(".gamerules.spawnChunkRadius"))a.set(i,new Field(field.document(),field.path(),field.kind(),field.choices(),field.registry(),"Larger radius increases RAM and CPU processing requirements. Setting to 0 disables spawn retention. spawn_chunks.radius overrides the per-dimension spawnChunkRadius gamerule; omit it to follow the gamerule."));}
        return List.copyOf(a);
    }
    private static void add(List<Field>a,String path,Kind kind){String help=path.startsWith("state.initial.")?"Applied when independent world state is first created; reopening restores saved state.":path.startsWith("state.on_load.")?"Reapplied whenever the level is constructed, replacing the saved value.":path.equals("generation.seed")?"New chunks use this seed on the next safe level load. Existing terrain remains.":path.startsWith("player.loadouts.")?"Applies after inventory-group restoration. Unavailable accessory slots are skipped and reported to the player.":path.equals("lighting.brightness_table")?"Exactly 16 finite brightness values, for light levels 0 through 15. Does not relight chunks.":"Dimension setting. Validate on the server to check ranges, required companion fields and installed addons.";String registry=path.endsWith("dimension")||path.endsWith("destination")||path.equals("lifecycle.fallback")||path.equals("access.fallback")?"minecraft:dimension":path.equals("spawn.fallback_block")?"minecraft:block":path.equals("render.music.sound")?"minecraft:sound_event":"";a.add(new Field("properties",path,kind,List.of(),registry,help));}
    private static void many(List<Field>a,String paths,Kind kind){for(String p:paths.split(" "))add(a,p,kind);}
    private static void bool(List<Field>a,String p){many(a,p,Kind.BOOLEAN);}private static void integer(List<Field>a,String p){many(a,p,Kind.INTEGER);}private static void number(List<Field>a,String p){many(a,p,Kind.NUMBER);}private static void text(List<Field>a,String p){many(a,p,Kind.TEXT);}private static void resource(List<Field>a,String p){many(a,p,Kind.RESOURCE);}private static void json(List<Field>a,String p){many(a,p,Kind.JSON);}
    private static void choice(List<Field>a,String p,String choices){add(a,p,Kind.CHOICE);var old=a.removeLast();a.add(new Field(old.document(),p,Kind.CHOICE,List.of(choices.split(" ")),"",old.help()));}
    private JEDBuilderFields(){}
}
