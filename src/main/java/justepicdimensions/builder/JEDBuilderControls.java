package justepicdimensions.builder;

import java.util.*;

/** Every field has an explicit input policy. Registry contents come from the owning runtime. */
public final class JEDBuilderControls {
    public enum Control { FIXED, RUNTIME, TERRAIN, BIOME_RULES, STRUCTURED, GROUP, MANUAL }
    public static List<String> fixed(JEDBuilderFields.Field field){
        return field.kind()==JEDBuilderFields.Kind.BOOLEAN?List.of("false","true"):field.choices();
    }
    public static String registry(JEDBuilderFields.Field field){
        String path=field.path();
        if(!field.registry().isEmpty())return field.registry();
        if(path.equals("access.rule"))return "justepicdimensions:access_rules";
        if(path.equals("lifecycle.schedule"))return "justepicdimensions:lifecycle_schedules";
        if(path.equals("monitoring.policy"))return "justepicdimensions:monitoring";if(path.equals("instances.policy"))return "justepicdimensions:instance_policies";if(path.equals("pregeneration.policy"))return "justepicdimensions:pregeneration";if(path.equals("environment.policy"))return "justepicdimensions:environment";
        if(path.equals("mobs.policy"))return "justepicdimensions:mob_spawning";
        if(path.equals("provider.type"))return "justepicdimensions:providers";
        if(path.equals("lifecycle.template"))return "justepicdimensions:templates";
        if(path.equals("spawn_structure.asset"))return "justepicdimensions:structures";
        if(path.endsWith(".kit"))return "justepicdimensions:loadouts";
        if(path.startsWith("player.groups."))return "justepicdimensions:player_groups/"+path.substring("player.groups.".length());
        if(path.endsWith(".texture"))return "client:textures";
        if(path.endsWith(".renderer.id"))return "client:renderers/"+path.split("\\.")[1];
        return "";
    }
    public static Control control(JEDBuilderFields.Field field){
        if(!fixed(field).isEmpty())return Control.FIXED;
        if(field.document().equals("dimension")&&field.path().equals("generator"))return Control.TERRAIN;
        if(!registry(field).isEmpty())return Control.RUNTIME;
        if(field.path().startsWith("render.biome_colors."))return Control.BIOME_RULES;
        if(Set.of("brightness","vector","offset","commands","ids").contains(JEDBuilderFieldInfo.jsonType(field)))return Control.STRUCTURED;
        if(field.path().equals("provider.settings")||field.path().endsWith(".gamerules"))return Control.GROUP;
        return Control.MANUAL;
    }
    private JEDBuilderControls(){}
}
