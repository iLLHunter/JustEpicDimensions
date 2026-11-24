package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.JEDText;
import net.minecraft.network.chat.Component;

/** Localized presentation metadata. Native codecs remain the authority for complete definitions. */
public final class JEDBuilderFieldInfo {
    public static Component tr(String key,Object...args){return JEDText.tr("justepicdimensions.builder."+key,args);}
    public static Component label(JEDBuilderFields.Field field){
        if(field.path().equals("mobs.creature_cap"))return tr("creature_cap");
        String path=field.path();if(path.equals("monitoring.policy"))return Component.translatableWithFallback("justepicdimensions.monitor.policy","Monitoring policy");if(path.equals("monitoring.enabled"))return Component.translatableWithFallback("justepicdimensions.monitor.enabled","Record monitoring history");if(path.equals("instances.policy"))return Component.translatableWithFallback("justepicdimensions.instance.policy","Instance policy");if(path.equals("pregeneration.policy"))return tr("pregeneration_policy");if(path.equals("profiles"))return Component.translatableWithFallback("justepicdimensions.builder.profiles","Setting profiles");if(path.equals("lifecycle.schedule"))return tr("lifecycle_schedule");if(path.equals("environment.policy"))return tr("environment_policy");if(path.equals("mobs.policy"))return tr("mob_policy");if(path.startsWith("access."))return tr("access_label."+path.substring(7));var result=Component.empty();
        for(String part:path.split("\\.")){
            if(!result.getSiblings().isEmpty())result.append(" / ");
            if(path.contains(".gamerules.")&&part.equals(path.substring(path.lastIndexOf('.')+1)))result.append(Component.translatable("gamerule."+part));
            else result.append(tr("word."+part));
        }
        return result;
    }
    public static Component help(JEDBuilderFields.Field field){
        if(field.path().equals("profiles"))return Component.translatableWithFallback("justepicdimensions.builder.help.profiles","Ordered reusable profiles. Later profiles win; local settings override them. Arrays replace. Use the list editor to select profiles and Advanced JSON to reorder them.");
        var result=description(field).copy();
        if(field.path().startsWith("state.initial."))result.append("\n").append(tr("timing_initial"));
        else if(field.path().startsWith("state.on_load."))result.append("\n").append(tr("timing_load"));
        if(JEDBuilderControls.control(field)==JEDBuilderControls.Control.FIXED||JEDBuilderControls.control(field)==JEDBuilderControls.Control.RUNTIME||JEDBuilderControls.control(field)==JEDBuilderControls.Control.TERRAIN)return result.append("\n").append(tr("select_help"));
        double[] bounds=bounds(field);if(bounds!=null)result.append("\n").append(tr("range",format(bounds[0]),format(bounds[1])));
        if(field.kind()!=JEDBuilderFields.Kind.JSON)result.append("\n").append(tr("example",example(field)));
        return result;
    }
    public static Component advancedHelp(JEDBuilderFields.Field field){
        var result=description(field).copy();
        result.append("\n").append(tr("accepted",accepted(field)));
        double[] bounds=bounds(field);if(bounds!=null)result.append("\n").append(tr("range",format(bounds[0]),format(bounds[1])));
        if(field.kind()==JEDBuilderFields.Kind.CHOICE)result.append("\n").append(tr("choices",String.join(", ",field.choices())));
        if(field.path().startsWith("state.initial."))result.append("\n").append(tr("timing_initial"));
        else if(field.path().startsWith("state.on_load."))result.append("\n").append(tr("timing_load"));
        result.append("\n").append(tr("example",example(field))).append("\n").append(tr("path",field.document()+":"+field.path()));
        return result;
    }
    public static Component description(JEDBuilderFields.Field f){
        if(f.path().equals("profiles"))return Component.translatableWithFallback("justepicdimensions.builder.help.profiles","Applied in order. Later profiles win; local settings win last. Arrays replace.");
        String p=f.path();String key;
        if(p.equals("monitoring.policy"))return Component.translatableWithFallback("justepicdimensions.monitor.policy.help","Reusable sampling, retention, disk refresh and optional alert/action settings.");
        if(p.equals("monitoring.enabled"))return Component.translatableWithFallback("justepicdimensions.monitor.enabled.help","Override the global recording default for this dimension. Live operator diagnostics remain available.");
        if(p.equals("instances.policy"))return Component.translatableWithFallback("justepicdimensions.instance.policy.help","Reusable ownership, permissions, state scopes, seeds and cleanup policy.");
        if(p.equals("pregeneration.policy"))return tr("help.pregeneration_policy");
        if(p.contains(".gamerules.")){
            String id=p.substring(p.lastIndexOf('.')+1);
            if(id.equals("spawnChunkRadius"))return tr("help.spawn_radius");
            return Component.translatableWithFallback("gamerule."+id+".description",Component.translatable("gamerule."+id).getString());
        }
        if(p.startsWith("access."))key="access."+p.substring(7);
        else if(p.startsWith("render.biome_colors."))key="biome_rules";
        else if(p.equals("lighting.brightness_table"))key="brightness";
        else if(p.equals("lighting.client_light_check"))key="light_check";
        else if(p.equals("display_name"))key="display_name";
        else if(p.equals("player.inventory_group"))key="inventory_group";
        else if(p.equals("player.remove_global_inventory_items"))key="remove_global_inventory_items";
        else if(p.equals("player.force_game_mode"))key="force_mode";
        else if(p.startsWith("player.groups."))key="category_group";
        else if(p.startsWith("player.loadouts."))key="loadouts";
        else if(p.equals("provider.type"))key="provider";
        else if(p.equals("provider.settings.foggy_biomes"))key="fog_biomes";
        else if(p.equals("provider.settings.listed_have_fog"))key="fog_list_mode";
        else if(p.equals("provider.settings.water_vaporizes"))key="vaporize";
        else if(p.equals("provider.settings.surface_world"))key="surface";
        else if(p.equals("provider.settings.fog"))key="provider_fog";
        else if(p.equals("provider.settings.spawn_height"))key="spawn_height";
        else if(p.equals("provider.settings.horizon"))key="horizon";
        else if(p.equals("provider.settings"))key="provider_settings";
        else if(p.equals("generation.seed"))key="seed";
        else if(p.equals("generation.structures"))key="generation_structures";
        else if(p.equals("lifecycle.post_delete_commands"))key="post_delete";
        else if(p.equals("lifecycle.template"))key="template";
        else if(p.equals("lifecycle.enabled"))key="enabled";
        else if(p.equals("lifecycle.load_on_start"))key="startup";
        else if(p.equals("lifecycle.keep_loaded"))key="keep_loaded";
        else if(p.equals("lifecycle.automatic_unload")||p.equals("lifecycle.idle_seconds"))key="unload";
        else if(p.equals("lifecycle.temporary"))key="temporary";
        else if(p.equals("lifecycle.fallback"))key="fallback";
        else if(p.startsWith("spawn_chunks."))key=p.endsWith("radius")?"spawn_radius":p.endsWith("activity")?"spawn_activity":"spawn_retention";
        else if(p.equals("time.day_length")||p.equals("time.night_length"))key="cycle_length";
        else if(p.startsWith("time.angle_range."))key="angle_range";
        else if(p.startsWith("time.clock_range."))key="clock_range";
        else if(p.equals("time.celestial"))key="celestial";
        else if(p.equals("sleep.mode"))key="sleep";
        else if(p.equals("sleep.bed_checkpoints"))key="bed_checkpoint";
        else if(p.startsWith("respawn."))key="respawn";
        else if(p.equals("weather.snow")||p.equals("weather.ice"))key="snow_ice";
        else if(p.equals("weather.natural_lightning"))key="lightning";
        else if(p.equals("weather.precipitation_block_effects"))key="rain_effects";
        else if(p.equals("weather.preserve_on_sleep"))key="sleep_weather";
        else if(p.equals("lifecycle.schedule"))key="lifecycle_schedule";
        else if(p.equals("environment.policy"))key="environment_policy";
        else if(p.equals("generation.structure_sets"))key="structure_sets";
        else if(p.equals("mobs.policy"))key="mob_policy";
        else if(p.equals("mobs.creature_cap"))key="creature_cap";
        else if(p.startsWith("mobs."))key="mobs";
        else if(p.startsWith("end."))key="end";
        else if(p.startsWith("spawn_structure."))key="structure";
        else if(p.equals("spawn.y_offset"))key="spawn_offset";
        else if(p.equals("spawn.reset_velocity")||p.equals("spawn.reset_fall_distance"))key="spawn_arrival";
        else if(p.startsWith("spawn."))key="spawn";
        else if(p.startsWith("border."))key="border";
        else if(p.startsWith("travel."))key="travel";
        else if(p.endsWith("day_time_per_tick"))key="clock_speed";
        else if(p.endsWith("day_time")||p.endsWith("day_time_fraction"))key="day_time";
        else if(p.endsWith("game_time"))key="game_time";
        else if(p.equals("state.follow_dimension_hardcore_rules"))key="hardcore_rules";
        else if(p.startsWith("state."))key="world_state";
        else if(p.equals("type")&&f.document().equals("dimension"))key="native_type";
        else if(p.equals("generator"))key="generator";
        else if(p.startsWith("render.")&&p.contains("renderer"))key="renderer";
        else if(p.startsWith("render.sky.sun.")||p.startsWith("render.sky.moon."))key="body";
        else if(p.startsWith("render.sky."))key="sky";
        else if(p.startsWith("render.clouds."))key="clouds";
        else if(p.startsWith("render.fog."))key="fog";
        else if(p.startsWith("render.music."))key="music";
        else if(p.startsWith("render.weather."))key="weather_render";
        else throw new IllegalArgumentException("Missing builder help for "+f.document()+":"+p);
        return tr("help."+key);
    }
    public static Component accepted(JEDBuilderFields.Field f){
        if(f.kind()==JEDBuilderFields.Kind.JSON)return tr("type."+jsonType(f));
        if(f.kind()==JEDBuilderFields.Kind.CHOICE)return Component.literal(String.join(", ",f.choices()));
        if(f.kind()==JEDBuilderFields.Kind.SEED)return Component.literal("12345 / \"…\"");
        return tr("type."+f.kind().name().toLowerCase(Locale.ROOT));
    }
    public static String jsonType(JEDBuilderFields.Field f){String p=f.path();return p.equals("profiles")?"ids":f.document().equals("dimension")&&p.equals("generator")?"generator":p.equals("provider.settings")||p.equals("access.message")||p.endsWith(".renderer.settings")?"any_json":p.startsWith("render.biome_colors.")?"rules":p.equals("lighting.brightness_table")?"brightness":p.equals("provider.settings.foggy_biomes")?"ids":p.equals("lifecycle.post_delete_commands")?"commands":p.equals("spawn_structure.offset")?"offset":p.startsWith("travel.")?"vector":"json";}
    public static double[] bounds(JEDBuilderFields.Field f){String p=f.path();
        if(p.equals("mobs.creature_cap"))return new double[]{0,Integer.MAX_VALUE};
        if(p.equals("render.music.min_delay")||p.equals("render.music.max_delay"))return new double[]{0,72000};
        if(p.equals("lifecycle.idle_seconds"))return new double[]{1,31536000};
        if(p.equals("spawn_chunks.radius")||p.endsWith(".gamerules.spawnChunkRadius"))return new double[]{0,32};
        if(p.equals("provider.settings.spawn_height"))return new double[]{-2032,2031};
        if(p.equals("spawn_structure.load_around"))return new double[]{0,128};
        if(p.equals("travel.check_interval"))return new double[]{1,12000};
        if(p.equals("travel.cooldown"))return new double[]{0,12000};
        if(p.equals("time.day_length")||p.equals("time.night_length"))return new double[]{1,Integer.MAX_VALUE};
        if(p.startsWith("time.angle_range.")||Set.of("render.sky.biome_blend","render.sky.light_blend","render.sky.brightness","render.sky.brightness_factor").contains(p))return new double[]{0,1};
        if(p.endsWith(".scale")&&p.startsWith("render.sky."))return new double[]{0,16};
        if(p.equals("render.fog.far"))return new double[]{.001,32768};
        if(p.equals("render.clouds.height")||p.equals("render.fog.near"))return new double[]{-32768,32768};
        return null;
    }
    public static String example(JEDBuilderFields.Field f){String p=f.path();if(p.equals("profiles"))return "[\"example:frozen\",\"example:hard_mode\"]";
        if(p.equals("mobs.creature_cap"))return "32";
        if(f.kind()==JEDBuilderFields.Kind.CHOICE)return f.choices().getFirst();
        if(f.kind()==JEDBuilderFields.Kind.BOOLEAN)return "true";
        if(f.kind()==JEDBuilderFields.Kind.COLOR)return "#20002F";
        if(f.kind()==JEDBuilderFields.Kind.RESOURCE)return p.equals("provider.type")?"justepicdimensions:surface":p.equals("type")?"minecraft:overworld":p.equals("render.music.sound")?"minecraft:music.overworld.forest":p.equals("spawn_structure.asset")?"example:spawn.nbt":"example:moon";
        if(f.kind()==JEDBuilderFields.Kind.SEED)return "12345";
        if(f.kind()==JEDBuilderFields.Kind.TEXT)return p.equals("display_name")?"My dimension":p.equals("player.inventory_group")?"survival":"example";
        if(f.kind()==JEDBuilderFields.Kind.INTEGER||f.kind()==JEDBuilderFields.Kind.NUMBER){var bounds=bounds(f);return p.endsWith("day_time")?"11000":bounds==null?"1":format(Math.max(bounds[0],Math.min(bounds[1],1)));}
        return switch(jsonType(f)){case "generator"->"{\"type\":\"minecraft:noise\",\"biome_source\":{\"type\":\"minecraft:multi_noise\",\"preset\":\"minecraft:overworld\"},\"settings\":\"minecraft:overworld\"}";case "rules"->"[{\"biome\":\"minecraft:jungle\",\"color\":\"#20002F\"}]";case "brightness"->"[0,0.07,0.13,0.2,0.27,0.33,0.4,0.47,0.53,0.6,0.67,0.73,0.8,0.87,0.93,1]";case "ids"->"[\"minecraft:jungle\"]";case "commands"->"[\"say Dimension reset\"]";case "offset","vector"->"[0,0,0]";default->"{}";};
    }
    private static String format(double v){return v==(long)v?Long.toString((long)v):Double.toString(v);}
    private JEDBuilderFieldInfo(){}
}
