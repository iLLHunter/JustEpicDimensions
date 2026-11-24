package justepicdimensions.profile;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Named partial property documents, composed with the ordinary JED overlay and origin rules. */
public final class JEDSettingProfiles {
    public static final String DIRECTORY="justepicdimensions/profiles";
    public static final Set<String> SETTINGS=Set.of("provider","lighting","reset","lifecycle","state","time","weather","mobs","spawn","spawn_chunks","sleep","respawn","player","generation","border","end","travel","display_name","spawn_structure","render","access","environment","pregeneration","instances","monitoring");
    private final Function<ResourceLocation,JEDDefinitionResolver.Result> resources;
    private final Map<ResourceLocation,List<JEDSourceDocument>> cache=new HashMap<>();
    private final Set<ResourceLocation> visiting=new LinkedHashSet<>();
    public JEDSettingProfiles(Function<ResourceLocation,JEDDefinitionResolver.Result> resources){this.resources=resources;}
    public static boolean isProfile(ResourceLocation key){return key.getPath().startsWith(DIRECTORY+"/");}
    public static ResourceLocation resource(ResourceLocation id){return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),DIRECTORY+"/"+id.getPath()+".json");}
    public static List<ResourceLocation> references(JsonObject json,String at){
        if(!json.has("profiles"))return List.of();var raw=json.get("profiles");if(!raw.isJsonArray()||raw.getAsJsonArray().size()>128)throw new JEDDefinitionException(at+": profiles must be an ordered array of at most 128 IDs");
        var result=new ArrayList<ResourceLocation>();var seen=new HashSet<ResourceLocation>();for(var entry:raw.getAsJsonArray()){var id=JEDDimensionDefinition.id(JEDJson.string(entry,at+".profiles"),at);if(!seen.add(id))throw new JEDDefinitionException(at+": duplicate profile "+id);result.add(id);}return List.copyOf(result);
    }
    public static void validate(ResourceLocation id,JEDSourceDocument document){
        var root=JEDJson.object(document.value(),id.toString());JEDJson.keys(root,Set.of("format_version","profiles","settings"),id.toString());
        if(JEDJson.integer(root.get("format_version"),id+".format_version")!=1)throw new JEDDefinitionException(id+": unsupported profile version");references(root,id.toString());
        var settings=JEDJson.object(root.get("settings"),id+".settings");JEDJson.keys(settings,SETTINGS,id+".settings");
    }
    private List<JEDSourceDocument> layers(ResourceLocation id){
        var known=cache.get(id);if(known!=null)return known;if(visiting.size()>=32||!visiting.add(id))throw new JEDDefinitionException("Profile cycle or depth over 32: "+visiting+" -> "+id);
        try{
            var input=resources.apply(resource(id));if(input==null||input.document()==null)throw new JEDDefinitionException("Missing setting profile "+id);validate(id,input.document());var value=input.document().value().getAsJsonObject();var result=new ArrayList<JEDSourceDocument>();
            for(var parent:references(value,id.toString())){result.addAll(layers(parent));if(result.size()>4096)throw new JEDDefinitionException(id+": profile expansion exceeds 4096 layers");}
            var local=new JsonObject();local.addProperty("format_version",1);value.getAsJsonObject("settings").entrySet().forEach(e->local.add(e.getKey(),e.getValue().deepCopy()));var origins=new TreeMap<String,String>();origins.put("",input.document().origin(""));for(var e:input.document().origins().entrySet())if(e.getKey().startsWith("settings."))origins.put(e.getKey().substring(9),e.getValue());result.add(new JEDSourceDocument(local,origins));var immutable=List.copyOf(result);cache.put(id,immutable);return immutable;
        }finally{visiting.remove(id);}
    }
    public JEDDefinitionResolver.Result profile(ResourceLocation id){return compose(id.toString(),null,layers(id),Function.identity());}
    public JEDDefinitionResolver.Result expand(ResourceLocation key,JEDSourceDocument document){return apply(key,null,document,Function.identity());}
    public JEDDefinitionResolver.Result apply(ResourceLocation key,JEDSourceDocument lower,JEDSourceDocument document,Function<JEDSourceDocument,JEDSourceDocument> filter){
        if(document==null)return new JEDDefinitionResolver.Result(lower,List.of());var root=JEDJson.object(document.value(),key.toString());var parts=new ArrayList<JEDSourceDocument>();for(var id:references(root,key.toString())){parts.addAll(layers(id));if(parts.size()>4096)throw new JEDDefinitionException(key+": profile expansion exceeds 4096 layers");}root.remove("profiles");parts.add(new JEDSourceDocument(root,document.origins()));return compose(key.toString(),lower,parts,filter);
    }
    private JEDDefinitionResolver.Result compose(String key,JEDSourceDocument lower,List<JEDSourceDocument> layers,Function<JEDSourceDocument,JEDSourceDocument> filter){
        var conflicts=new ArrayList<JEDDefinitionResolver.Conflict>();var base=lower;for(var layer:layers){var selected=filter.apply(layer);if(selected==null)continue;var merged=JEDDefinitionResolver.overlay(key,base,selected,true);base=merged.document();conflicts.addAll(merged.conflicts());}return new JEDDefinitionResolver.Result(base,conflicts);
    }
}
