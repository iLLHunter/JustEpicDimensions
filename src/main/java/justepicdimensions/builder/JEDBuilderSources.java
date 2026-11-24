package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Config-only copy instructions. Native codecs and clients receive ordinary resolved JSON. */
public final class JEDBuilderSources {
    public static final String HEADER = "_jed_builder";
    private final Map<ResourceLocation,JEDSourceDocument> pack, saved, json;
    private final Map<ResourceLocation,JEDDefinitionResolver.Result> resolved = new TreeMap<>();
    private final Set<ResourceLocation> visiting = new LinkedHashSet<>();
    private final JEDDimensionOrigins origins;
    private final JEDConfigControls controls;
    private final List<String> notices;
    private final justepicdimensions.profile.JEDSettingProfiles profiles=new justepicdimensions.profile.JEDSettingProfiles(this::resolve);
    private final boolean expandProfiles;
    public JEDBuilderSources(Map<ResourceLocation,JEDSourceDocument> pack, Map<ResourceLocation,JEDSourceDocument> saved,
            Map<ResourceLocation,JEDSourceDocument> json, JEDDimensionOrigins origins, JEDConfigControls controls, List<String> notices) {
        this(pack,saved,json,origins,controls,notices,true);
    }
    public JEDBuilderSources(Map<ResourceLocation,JEDSourceDocument> pack,Map<ResourceLocation,JEDSourceDocument> saved,Map<ResourceLocation,JEDSourceDocument> json,JEDDimensionOrigins origins,JEDConfigControls controls,List<String> notices,boolean expandProfiles){
        this.pack=pack;this.saved=saved;this.json=json;this.origins=origins;this.controls=controls;this.notices=notices;this.expandProfiles=expandProfiles;
    }
    public JEDDefinitionResolver.Result resolve(ResourceLocation key) {
        if(resolved.containsKey(key))return resolved.get(key);
        if(!visiting.add(key))throw new JEDDefinitionException("Builder inheritance cycle: "+visiting+" -> "+key);
        try {
            boolean properties=key.getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/");
            var lower=pack.getOrDefault(key,saved.get(key));
            var conflicts=new ArrayList<JEDDefinitionResolver.Conflict>();
            if(properties&&lower!=null){var expanded=expandProfiles?profiles.expand(key,lower):JEDDefinitionResolver.overlay(key.toString(),null,lower,true);lower=expanded.document();conflicts.addAll(expanded.conflicts());}
            var higher=json.get(key);
            String mode="overlay";JsonObject header=null;
            if(higher!=null&&higher.value().isJsonObject()&&higher.value().getAsJsonObject().has(HEADER)) {
                var value=higher.value().getAsJsonObject();header=JEDJson.object(value.remove(HEADER),key+"."+HEADER);
                JEDJson.keys(header,Set.of("mode","source","fallback"),key+"."+HEADER);
                mode=JEDJson.string(header.get("mode"),key+".mode");
                if(!mode.equals("independent")&&!mode.equals("inherit"))throw new JEDDefinitionException(key+": invalid builder copy mode "+mode);
                if(mode.equals("independent")&&(header.has("source")||header.has("fallback")))throw new JEDDefinitionException(key+": independent copies cannot have an inheritance source");
                higher=new JEDSourceDocument(value,higher.origin(""));
            }
            // Permissions apply after expansion and before copy instructions.
            var filtered=origins.filter(key,lower,higher,controls,notices);
            if(filtered==null)mode="overlay";
            if(filtered!=null&&mode.equals("inherit")) {
                var source=JEDDimensionDefinition.id(JEDJson.string(header.get("source"),key+".source"),key.toString());
                if(!directory(key).equals(directory(source)))
                    throw new JEDDefinitionException(key+": inheritance source must use the same resource directory");
                var inherited=source.equals(key)?lower:resolve(source).document();
                if(inherited==null&&header.has("fallback"))inherited=new JEDSourceDocument(header.get("fallback"),filtered.origin("")+" (source fallback)");
                if(inherited==null)throw new JEDDefinitionException(key+": missing inheritance source "+source);
                if(properties&&!source.equals(key))inherited=new JEDSourceDocument(justepicdimensions.instance.JEDInstanceStore.blueprint(inherited.value()),inherited.origins());
                lower=inherited;
            } else if(filtered!=null&&mode.equals("independent")) {
                if(lower!=null&&!lower.value().equals(filtered.value()))conflicts.add(new JEDDefinitionResolver.Conflict(key.toString(),"$",lower.origin(""),lower.value().toString(),filtered.origin(""),"<independent document>"));
                // A disabled biome override must retain the native selection even in a complete snapshot.
                boolean filteredBiome=!controls.biomes()&&key.getPath().startsWith("dimension/")&&origins.existing().contains(JEDRegistryResources.entry(key,"dimension"));
                if(!filteredBiome)lower=null;
            }
            var filterBase=lower;var result=properties&&expandProfiles?profiles.apply(key,lower,filtered,part->origins.filter(key,filterBase,part,controls,notices)):JEDDefinitionResolver.overlay(key.toString(),lower,filtered,properties);conflicts.addAll(result.conflicts());
            var complete=new JEDDefinitionResolver.Result(result.document(),conflicts);resolved.put(key,complete);return complete;
        } finally { visiting.remove(key); }
    }
    public static JsonObject instruction(String mode,ResourceLocation source,JsonElement fallback) {
        var header=new JsonObject();header.addProperty("mode",mode);
        if(mode.equals("inherit")){header.addProperty("source",source.toString());if(fallback!=null)header.add("fallback",fallback.deepCopy());}
        return header;
    }
    public justepicdimensions.profile.JEDSettingProfiles profiles(){return profiles;}
    private static String directory(ResourceLocation id){for(String policy:List.of(justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY,justepicdimensions.instance.JEDInstancePolicy.DIRECTORY,justepicdimensions.access.JEDAccessRule.DIRECTORY,justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY,justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY,justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY,justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY))if(id.getPath().startsWith(policy+"/"))return policy;if(justepicdimensions.profile.JEDSettingProfiles.isProfile(id))return justepicdimensions.profile.JEDSettingProfiles.DIRECTORY;if(id.getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/"))return JEDDimensionDefinition.DIRECTORY;var group=justepicdimensions.player.JEDPlayerCategory.resourceCategory(id);if(group.isPresent())return group.get().directory();var nativeDirectory=JEDRegistryResources.directory(id);return nativeDirectory!=null?nativeDirectory:justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY;}
    private JEDBuilderSources(){throw new AssertionError();}
}
