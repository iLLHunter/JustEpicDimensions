package justepicdimensions.definition;

import com.google.gson.*;
import com.mojang.serialization.*;
import justepicdimensions.lighting.JEDClientLightPolicy;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** An immutable decoded behavior definition, keyed by the target level rather than DimensionType. */
public record JEDDimensionDefinition(ResourceLocation id, JEDProviderFactoryRegistry.Selection provider,
                                     JEDClientLightPolicy clientLightPolicy, JEDSourceDocument source) {
    public static final String DIRECTORY = "justepicdimensions/dimension_properties";
    public justepicdimensions.render.JEDRenderDefinition render(){var root=source.value().getAsJsonObject();return root.has("render")?justepicdimensions.render.JEDRenderDefinition.decode(root.get("render"),id+".render"):null;}
    public justepicdimensions.travel.JEDTravelDefinition travel(){var root=source.value().getAsJsonObject();return root.has("travel")?justepicdimensions.travel.JEDTravelDefinition.decode(root.get("travel"),id+".travel"):justepicdimensions.travel.JEDTravelDefinition.DEFAULT;}
    public justepicdimensions.access.JEDAccessDefinition access(){var root=source.value().getAsJsonObject();return root.has("access")?justepicdimensions.access.JEDAccessDefinition.decode(root.get("access"),id+".access"):justepicdimensions.access.JEDAccessDefinition.DEFAULT;}
    public ResourceLocation lifecycleSchedule(){var root=source.value().getAsJsonObject();if(!root.has("lifecycle"))return null;var json=JEDJson.object(root.get("lifecycle"),id+".lifecycle");return json.has("schedule")?id(JEDJson.string(json.get("schedule"),id+".lifecycle.schedule"),id.toString()):null;}
    public ResourceLocation instances(){var root=source.value().getAsJsonObject();if(!root.has("instances"))return null;var json=JEDJson.object(root.get("instances"),id+".instances");JEDJson.keys(json,Set.of("policy"),id+".instances");return JEDDimensionDefinition.id(JEDJson.string(json.get("policy"),"instances.policy"),id.toString());}
    public ResourceLocation pregeneration(){var root=source.value().getAsJsonObject();if(!root.has("pregeneration"))return null;var settings=JEDJson.object(root.get("pregeneration"),id+".pregeneration");JEDJson.keys(settings,Set.of("policy"),id+".pregeneration");return settings.has("policy")?id(JEDJson.string(settings.get("policy"),id+".pregeneration.policy"),id.toString()):null;}
    public ResourceLocation environment(){var root=source.value().getAsJsonObject();if(!root.has("environment"))return null;var settings=JEDJson.object(root.get("environment"),id+".environment");JEDJson.keys(settings,Set.of("policy"),id+".environment");return settings.has("policy")?id(JEDJson.string(settings.get("policy"),id+".environment.policy"),id.toString()):null;}
    public String displayName(){var root=source.value().getAsJsonObject();if(!root.has("display_name"))return null;String name=JEDJson.string(root.get("display_name"),id+".display_name");if(name.isBlank()||name.length()>256)throw new JEDDefinitionException(id+": display_name must contain 1..256 characters");return name;}
    public justepicdimensions.worldgen.JEDWorldgenDefinition generation(){var root=source.value().getAsJsonObject();return root.has("generation")?justepicdimensions.worldgen.JEDWorldgenDefinition.decode(root.get("generation"),id+".generation"):justepicdimensions.worldgen.JEDWorldgenDefinition.DEFAULT;}
    public justepicdimensions.world.JEDBorderDefinition border(){var root=source.value().getAsJsonObject();return root.has("border")?justepicdimensions.world.JEDBorderDefinition.decode(root.get("border"),id+".border"):justepicdimensions.world.JEDBorderDefinition.DEFAULT;}
    public justepicdimensions.end.JEDEndDefinition end(){var root=source.value().getAsJsonObject();return root.has("end")?justepicdimensions.end.JEDEndDefinition.decode(root.get("end"),id+".end"):justepicdimensions.end.JEDEndDefinition.DEFAULT;}
    public justepicdimensions.structure.JEDStructureDefinition spawnStructure(){var root=source.value().getAsJsonObject();return root.has("spawn_structure")?justepicdimensions.structure.JEDStructureDefinition.decode(root.get("spawn_structure"),id+".spawn_structure"):null;}
    public justepicdimensions.spawn.JEDSpawnDefinition spawning(){return justepicdimensions.spawn.JEDSpawnDefinition.decode(source.value().getAsJsonObject(),id.toString());}
    public justepicdimensions.spawn.JEDSpawnChunksDefinition spawnChunks(){var root=source.value().getAsJsonObject();return root.has("spawn_chunks")?justepicdimensions.spawn.JEDSpawnChunksDefinition.decode(root.get("spawn_chunks"),id+".spawn_chunks"):justepicdimensions.spawn.JEDSpawnChunksDefinition.DEFAULT;}
    public justepicdimensions.player.JEDPlayerDefinition player(){var root=source.value().getAsJsonObject();return root.has("player")?justepicdimensions.player.JEDPlayerDefinition.decode(root.get("player"),id+".player"):justepicdimensions.player.JEDPlayerDefinition.DEFAULT;}
    private static final Set<String> RESET = Set.of("provider", "lighting", "lighting.client_light_check", "lighting.brightness_table", "state", "state.initial", "state.on_load", "state.follow_dimension_hardcore_rules", "lifecycle", "lifecycle.load_on_start", "lifecycle.keep_loaded", "lifecycle.automatic_unload", "lifecycle.idle_seconds", "lifecycle.temporary", "lifecycle.fallback", "lifecycle.template", "lifecycle.post_delete_commands");
    public justepicdimensions.world.JEDStateDefinition worldState(){var root=source.value().getAsJsonObject();return root.has("state")?justepicdimensions.world.JEDStateDefinition.decode(root.get("state"),id+".state"):null;}
    public justepicdimensions.time.JEDTimeDefinition time(){var root=source.value().getAsJsonObject();return root.has("time")?justepicdimensions.time.JEDTimeDefinition.decode(root.get("time"),id+".time"):null;}
    public justepicdimensions.weather.JEDWeatherDefinition weather(){var root=source.value().getAsJsonObject();return root.has("weather")?justepicdimensions.weather.JEDWeatherDefinition.decode(root.get("weather"),id+".weather"):null;}
    public justepicdimensions.mob.JEDMobSpawnDefinition mobs(){var root=source.value().getAsJsonObject();return root.has("mobs")?justepicdimensions.mob.JEDMobSpawnDefinition.decode(root.get("mobs"),id+".mobs"):null;}
    public justepicdimensions.lighting.JEDBrightnessTable brightnessTable() {
        var root=source.value().getAsJsonObject();
        var lighting=root.has("lighting")?root.getAsJsonObject("lighting"):null;
        return lighting!=null && lighting.has("brightness_table")?justepicdimensions.lighting.JEDBrightnessTable.decode(lighting.get("brightness_table")):null;
    }
    public record Lifecycle(boolean loadOnStart, boolean keepLoaded, boolean automaticUnload, int idleSeconds,
                            boolean temporary, ResourceLocation fallback, String template, List<String> commands) {}
    public Lifecycle lifecycle() {
        var root = source.value().getAsJsonObject();
        var settings = root.has("lifecycle") ? JEDJson.object(root.get("lifecycle"), id + ".lifecycle") : new JsonObject();
        JEDJson.keys(settings, Set.of("enabled", "load_on_start", "keep_loaded", "automatic_unload", "idle_seconds", "temporary", "fallback", "template", "post_delete_commands", "schedule"), id + ".lifecycle");
        configuredEnabled();
        int idle = settings.has("idle_seconds") ? JEDJson.integer(settings.get("idle_seconds"), id + ".lifecycle.idle_seconds") : 300;
        if (idle < 1 || idle > 86400 * 365) throw new JEDDefinitionException(id + ": idle_seconds must be between 1 and 31536000");
        var fallback = settings.has("fallback") ? id(JEDJson.string(settings.get("fallback"), id + ".fallback"), id.toString()) : ResourceLocation.parse("minecraft:overworld");
        if (fallback.equals(id) && !id.equals(ResourceLocation.parse("minecraft:overworld"))) throw new JEDDefinitionException(id + ": fallback cannot point to itself");
        String template = settings.has("template") ? JEDJson.string(settings.get("template"), id + ".template") : null;
        if (template != null && !template.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")) throw new JEDDefinitionException(id + ": template must be a relative name inside the selected configuration's templates directory");
        var commands = new ArrayList<String>();
        if (settings.has("post_delete_commands")) {
            if (!settings.get("post_delete_commands").isJsonArray()) throw new JEDDefinitionException(id + ": post_delete_commands must be an array");
            for (var value : settings.getAsJsonArray("post_delete_commands")) {
                String command = JEDJson.string(value, id + ".post_delete_commands");
                if (command.isBlank() || command.startsWith("/")) throw new JEDDefinitionException(id + ": use non-empty commands without a leading slash");
                commands.add(command);
            }
        }
        return new Lifecycle(flag(settings, "load_on_start"), flag(settings, "keep_loaded"), flag(settings, "automatic_unload"), idle,
            flag(settings, "temporary"), fallback, template, List.copyOf(commands));
    }
    /** Detached preparation reads metadata without executing registry-aware addon codecs or factories. */
    public static JEDDimensionDefinition metadata(ResourceLocation id,JEDSourceDocument source){return new JEDDimensionDefinition(id,null,null,source);}
    public boolean configuredEnabled(){
        var root=source.value().getAsJsonObject();if(!root.has("lifecycle"))return true;
        var lifecycle=JEDJson.object(root.get("lifecycle"),id+".lifecycle");
        return !lifecycle.has("enabled")||flag(lifecycle,"enabled");
    }
    private boolean flag(JsonObject settings, String key) {
        if (!settings.has(key)) return false;
        var value = settings.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new JEDDefinitionException(id + ".lifecycle." + key + ": expected a boolean");
        return value.getAsBoolean();
    }

    public static ResourceLocation id(String value, String source) {
        try {
            if (!value.contains(":")) throw new IllegalArgumentException("namespace required");
            var id = ResourceLocation.parse(value);
            for (String part : id.getPath().split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("invalid path segment");
            return id;
        } catch (IllegalArgumentException error) { throw new JEDDefinitionException(source + ": invalid namespaced identifier '" + value + "'", error); }
    }

    public static void validateReset(String path, String source) {
        if(Set.of("spawn_chunks","spawn_chunks.enabled","spawn_chunks.radius","spawn_chunks.activity").contains(path))return;
        if(path.equals("lifecycle.enabled")||path.equals("lifecycle.schedule"))return;
        if(path.equals("render")||path.startsWith("render.")){justepicdimensions.render.JEDRenderDefinition.reset(path,source);return;}
        if(Set.of("monitoring","monitoring.enabled","monitoring.policy").contains(path))return;
        if(path.equals("instances")||path.equals("instances.policy"))return;
        if(path.equals("pregeneration")||path.equals("pregeneration.policy")||path.equals("display_name")||path.equals("environment")||path.equals("environment.policy"))return;
        if(path.equals("access")||(path.startsWith("access.")&&justepicdimensions.access.JEDAccessDefinition.FIELDS.contains(path.substring(7))))return;
        if(path.equals("travel")||path.startsWith("travel.")){justepicdimensions.travel.JEDTravelDefinition.reset(path,source);return;}
        if(Set.of("spawn_structure","spawn_structure.asset","spawn_structure.rotation","spawn_structure.mirror","spawn_structure.centered","spawn_structure.offset","spawn_structure.load_around","spawn_structure.paste_biomes","generation","generation.seed","generation.structures","border","border.center_on_spawn","border.centering","end","end.dragon_fight","end.generate_spikes","end.reconstruct_spikes","provider.settings","provider.settings.surface_world","provider.settings.water_vaporizes","provider.settings.spawn_height","provider.settings.fog","provider.settings.foggy_biomes","provider.settings.listed_have_fog","provider.settings.horizon").contains(path))return;
        if(Set.of("player","player.inventory_group","player.force_game_mode","player.remove_global_inventory_items","player.groups","player.loadouts","player.loadouts.first_entry","player.loadouts.respawn","player.loadouts.every_entry").contains(path))return;
        if(path.startsWith("player.groups.")&&Arrays.stream(justepicdimensions.player.JEDPlayerCategory.values()).anyMatch(category->path.equals("player.groups."+category.key())))return;
        var parts=path.split("\\.");
        if(parts.length==4&&parts[0].equals("player")&&parts[1].equals("loadouts")){
            var fields=switch(parts[2]){case "first_entry"->Set.of("kit","mode","scope");case "every_entry"->Set.of("kit","mode");case "respawn"->Set.of("kit","mode","keep_inventory","grave_policy","grave_scope","unknown_grave");default->Set.<String>of();};
            if(fields.contains(parts[3]))return;
        }
        if (!RESET.contains(path) && !Set.of("time","time.day_length","time.night_length","time.celestial","time.angle_range","time.clock_range","time.clock_range.calendar_days_per_repeat","weather","weather.natural_lightning","weather.precipitation_block_effects","weather.snow","weather.ice","weather.preserve_on_sleep","mobs","mobs.hostile","mobs.peaceful","mobs.policy","mobs.creature_cap","spawn","spawn.mode","spawn.x","spawn.y","spawn.z","spawn.min_y","spawn.max_y","spawn.angle","spawn.fallback_block","spawn.ignore_suitability","spawn.y_offset","spawn.reset_velocity","spawn.reset_fall_distance","sleep","sleep.mode","sleep.bed_checkpoints","respawn","respawn.allow_here","respawn.destination","respawn.priority").contains(path)) throw new JEDDefinitionException(source + ": unknown or non-resettable property '" + path + "'");
    }

    public static JEDDimensionDefinition decode(ResourceLocation id, JEDSourceDocument source) {
        return decode(id, source, JsonOps.INSTANCE);
    }
    public static JEDDimensionDefinition decode(ResourceLocation id, JEDSourceDocument source, DynamicOps<JsonElement> ops) {
        var root = JEDJson.object(source.value(), id.toString());
        JEDJson.keys(root, Set.of("format_version", "provider", "lighting", "lifecycle", "state", "time", "weather", "mobs", "spawn", "spawn_chunks", "sleep", "respawn", "player", "generation", "border", "end", "display_name", "travel", "spawn_structure", "render", "access", "environment", "pregeneration", "instances", "monitoring", "_jed_instance"), id + " (" + source.origin("") + ")");
        if (JEDJson.integer(root.get("format_version"), id + ".format_version") != 1)
            throw new JEDDefinitionException(id + ": unsupported JED format_version (expected 1)");
        JEDProviderFactoryRegistry.Selection provider = root.has("provider")
            ? JEDProviderFactoryRegistry.decode(id,JEDJson.object(root.get("provider"), id + ".provider"), id + ".provider", ops) : null;
        JEDClientLightPolicy explicit = null;
        if (root.has("lighting")) {
            var lighting = JEDJson.object(root.get("lighting"), id + ".lighting");
            JEDJson.keys(lighting, Set.of("client_light_check", "brightness_table"), id + ".lighting");
            if (lighting.has("client_light_check")) {
                String mode = JEDJson.string(lighting.get("client_light_check"), id + ".lighting.client_light_check");
                try { explicit = JEDClientLightPolicy.parse(mode); }
                catch (IllegalArgumentException error) { throw new JEDDefinitionException(id + ": " + error.getMessage()); }
            }
        }
        var effective = explicit != null ? explicit : provider == null ? null
            : JEDClientLightPolicy.resolve(provider.type().profile(), null);
        var definition = new JEDDimensionDefinition(id, provider, effective, source); definition.lifecycle(); definition.brightnessTable(); definition.worldState(); definition.time(); definition.weather(); definition.mobs(); definition.spawning(); definition.spawnChunks(); definition.player(); definition.generation(); definition.border(); definition.end(); definition.travel(); definition.displayName(); definition.spawnStructure(); definition.render(); definition.access(); definition.environment(); definition.pregeneration(); definition.lifecycleSchedule(); definition.instances(); justepicdimensions.monitor.JEDMonitoringPolicy.binding(root); return definition;
    }
}
