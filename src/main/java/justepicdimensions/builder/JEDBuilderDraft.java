package justepicdimensions.builder;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Detached editor model shared by commands and GUI. Never contains live levels or registries. */
public final class JEDBuilderDraft {
    private JsonObject content;
    private final JsonArray undo;
    private JsonObject review;
    public JEDBuilderDraft(){content=fresh();undo=new JsonArray();}
    public JEDBuilderDraft(JsonObject saved) {
        JEDJson.keys(saved,Set.of("format_version","content","undo","review"),"builder draft");
        if(JEDJson.integer(saved.get("format_version"),"builder draft")!=1)throw new JEDDefinitionException("Unsupported builder draft format");
        content=JEDJson.object(saved.get("content"),"draft.content").deepCopy();
        if(content.has("target"))parseTarget(JEDJson.string(content.get("target"),"draft.target"));
        undo=saved.has("undo")?saved.getAsJsonArray("undo").deepCopy():new JsonArray();review=saved.has("review")?saved.getAsJsonObject("review").deepCopy():null;
    }
    private static JsonObject fresh(){var c=new JsonObject();c.addProperty("mode","independent");var docs=new JsonObject();docs.add("dimension",new JsonObject());var props=new JsonObject();props.addProperty("format_version",1);docs.add("properties",props);c.add("documents",docs);return c;}
    public JsonObject json(){var saved=new JsonObject();saved.addProperty("format_version",1);saved.add("content",content.deepCopy());saved.add("undo",undo.deepCopy());if(review!=null)saved.add("review",review.deepCopy());return saved;}
    public JsonObject content(){return content.deepCopy();}
    public JsonObject review(){return review==null?null:review.deepCopy();}
    public void reviewed(JsonObject value){review=value.deepCopy();}
    public void invalidateReview(){review=null;}
    public String mode(){return content.get("mode").getAsString();}
    public ResourceLocation source(){return content.has("source")?JEDDimensionDefinition.id(content.get("source").getAsString(),"draft source"):null;}
    public ResourceLocation target(){return content.has("target")?parseTarget(content.get("target").getAsString()):ResourceLocation.parse("justepicdimensions:new_dimension");}
    public static ResourceLocation parseTarget(String text){try{if(!text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException();var id=ResourceLocation.parse(text);if(Arrays.stream(id.getPath().split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))throw new IllegalArgumentException();return id;}catch(Exception error){throw justepicdimensions.JEDText.failure("justepicdimensions.builder.target_error");}}
    private void changing(){undo.add(content.deepCopy());review=null;}
    public void clear(){changing();content=fresh();}
    public void copy(ResourceLocation source,String mode,JsonObject documents,JsonObject fallbacks){var destination=target();changing();content=fresh();content.addProperty("target",destination.toString());content.addProperty("source",source.toString());content.addProperty("mode",mode);content.add("documents",documents.deepCopy());content.add("fallbacks",fallbacks.deepCopy());}
    public void undo(){if(undo.isEmpty())throw new JEDDefinitionException("Nothing to undo");content=undo.remove(undo.size()-1).getAsJsonObject();review=null;}
    public Set<String> documents(){return Set.copyOf(content.getAsJsonObject("documents").keySet());}
    public JsonElement document(String name){var value=content.getAsJsonObject("documents").get(name);if(value==null)throw justepicdimensions.JEDText.failure("builder.document_unknown",name);return value.deepCopy();}
    public void put(String name,JsonElement value){checkDocument(name);JEDJson.object(value,name);changing();content.getAsJsonObject("documents").add(name,value.deepCopy());}
    /** One explicit GUI update is one undo step. Copy instructions and history stay server owned. */
    public void updateDocuments(JsonObject documents){
        updateDocuments(documents,target());
    }
    public void updateDocuments(JsonObject documents,ResourceLocation target){
        parseTarget(target.toString());
        if(documents.size()>128)throw new JEDDefinitionException("A draft may contain at most 128 documents");
        documents.entrySet().forEach(e->{checkDocument(e.getKey());var doc=JEDJson.object(e.getValue(),e.getKey());if(doc.has(JEDBuilderSources.HEADER))throw new JEDDefinitionException("Builder metadata is managed by the editor");});
        if(content.getAsJsonObject("documents").equals(documents)&&target().equals(target))return;
        changing();content.add("documents",documents.deepCopy());content.addProperty("target",target.toString());
    }
    public void drop(String name){if(!documents().contains(name))throw justepicdimensions.JEDText.failure("builder.document_unknown",name);changing();content.getAsJsonObject("documents").remove(name);}
    public void set(String name,String path,JsonElement value){var doc=JEDJson.object(document(name),name);path(path);set(doc,path,value);put(name,doc);}
    public void remove(String name,String path){var doc=JEDJson.object(document(name),name);path(path);if(!remove(doc,path))throw justepicdimensions.JEDText.failure("builder.no_value",name+"."+path);put(name,doc);}
    public void reset(String path){JEDDimensionDefinition.validateReset(path,"builder reset");var doc=JEDJson.object(document("properties"),"properties");remove(doc,path);var resets=doc.has("reset")?doc.getAsJsonArray("reset"):new JsonArray();for(var value:resets)if(value.getAsString().equals(path))return;resets.add(path);doc.add("reset",resets);put("properties",doc);}
    public static void checkDocument(String name){if(name.equals("dimension")||name.equals("properties"))return;var id=JEDDimensionDefinition.id(name,"builder document");if(!id.getPath().endsWith(".json")||(!justepicdimensions.profile.JEDSettingProfiles.isProfile(id)&&JEDRegistryResources.directory(id)==null&&!id.getPath().startsWith(justepicdimensions.player.JEDLoadoutDefinition.DIRECTORY+"/")&&justepicdimensions.player.JEDPlayerCategory.resourceCategory(id).isEmpty()&&!id.getPath().startsWith(justepicdimensions.access.JEDAccessRule.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.mob.JEDMobSpawnPolicy.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.pregen.JEDPregenerationPolicy.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.monitor.JEDMonitoringPolicy.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.instance.JEDInstancePolicy.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.environment.JEDEnvironmentPolicy.DIRECTORY+"/")&&!id.getPath().startsWith(justepicdimensions.schedule.JEDLifecycleSchedulePolicy.DIRECTORY+"/")))throw new JEDDefinitionException("Use dimension, properties, or a native registry, named policy or profile resource such as example:justepicdimensions/profiles/frozen.json");}
    public static void path(String path){if(path.isBlank()||path.startsWith(JEDBuilderSources.HEADER)||Arrays.stream(path.split("\\.",-1)).anyMatch(String::isEmpty))throw new JEDDefinitionException("Use a nonempty dotted property path; builder metadata is managed by the editor");}
    public static JsonElement find(JsonElement root,String path){for(String key:path.split("\\.")){if(root==null||!root.isJsonObject())return null;root=root.getAsJsonObject().get(key);}return root;}
    public static void set(JsonObject root,String path,JsonElement value){String[] parts=path.split("\\.");var parent=root;for(int i=0;i<parts.length-1;i++){var next=parent.get(parts[i]);if(next==null){next=new JsonObject();parent.add(parts[i],next);}if(!next.isJsonObject())throw justepicdimensions.JEDText.failure("builder.non_object",parts[i]);parent=next.getAsJsonObject();}parent.add(parts[parts.length-1],value.deepCopy());
        if(root.has("reset")){var resets=root.getAsJsonArray("reset");for(int i=resets.size()-1;i>=0;i--){String reset=resets.get(i).getAsString();if(path.equals(reset)||path.startsWith(reset+".")||reset.startsWith(path+"."))resets.remove(i);}if(resets.isEmpty())root.remove("reset");}}
    private static boolean remove(JsonObject root,String path){String[] parts=path.split("\\.");var parents=new ArrayList<JsonObject>();var current=root;for(int i=0;i<parts.length-1;i++){parents.add(current);var value=current.get(parts[i]);if(value==null||!value.isJsonObject())return false;current=value.getAsJsonObject();}if(current.remove(parts[parts.length-1])==null)return false;for(int i=parents.size()-1;i>=0&&current.isEmpty();i--){parents.get(i).remove(parts[i]);current=parents.get(i);}return true;}
    public Map<ResourceLocation,JEDSourceDocument> materialize(ResourceLocation target) {
        var result=new TreeMap<ResourceLocation,JEDSourceDocument>();
        for(String name:documents()) {
            var key=resource(name,target);var value=JEDJson.object(document(name),name);
            ResourceLocation source=source()==null?key:resource(name,source());
            JsonElement fallback=content.has("fallbacks")?content.getAsJsonObject("fallbacks").get(name):null;
            // Auxiliary resources are complete documents unless the author explicitly supplied a copy instruction.
            if(name.equals("dimension")||name.equals("properties"))value.add(JEDBuilderSources.HEADER,JEDBuilderSources.instruction(mode(),source,fallback));
            result.put(key,new JEDSourceDocument(value,"JSON builder draft / "+target+" / "+name));
        }
        return Map.copyOf(result);
    }
    public static ResourceLocation resource(String name,ResourceLocation target){return name.equals("dimension")?ResourceLocation.fromNamespaceAndPath(target.getNamespace(),"dimension/"+target.getPath()+".json"):name.equals("properties")?ResourceLocation.fromNamespaceAndPath(target.getNamespace(),JEDDimensionDefinition.DIRECTORY+"/"+target.getPath()+".json"):JEDDimensionDefinition.id(name,"builder document");}
}
