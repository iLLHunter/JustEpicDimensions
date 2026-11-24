package justepicdimensions.instance;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;

/** Durable ownership/creation intents. Native catalogue records alone never grant instance access. */
public final class JEDInstanceStore {
    private static final Map<Path,Set<ResourceLocation>> PREPARING=new java.util.concurrent.ConcurrentHashMap<>();
    public static void preparing(Path world,ResourceLocation id,boolean active){world=world.toAbsolutePath().normalize();if(active)PREPARING.computeIfAbsent(world,p->java.util.concurrent.ConcurrentHashMap.newKeySet()).add(id);else{var ids=PREPARING.get(world);if(ids!=null){ids.remove(id);if(ids.isEmpty())PREPARING.remove(world,ids);}}}
    public static final String MARKER="_jed_instance";
    public record Instance(UUID uuid, ResourceLocation dimension,ResourceLocation source,ResourceLocation policy,
            String ownership,String owner,String ownerSystem,String run,String seed,JsonObject stem,JsonObject gameplay,
            String status,long created,long ticks,JsonObject progress,long retired,String trash,boolean automatic) {
        public Instance { stem=stem.deepCopy();gameplay=gameplay.deepCopy();progress=progress.deepCopy();if(ownership.equals("team")&&!justepicdimensions.teams.JEDTeamSystems.SYSTEMS.contains(ownerSystem))throw new JEDDefinitionException("Invalid instance owner system: "+ownerSystem); }
        public Instance(UUID uuid,ResourceLocation dimension,ResourceLocation source,ResourceLocation policy,String ownership,String owner,String run,String seed,JsonObject stem,JsonObject gameplay,String status,long created,long ticks,JsonObject progress,long retired,String trash,boolean automatic){this(uuid,dimension,source,policy,ownership,owner,"minecraft",run,seed,stem,gameplay,status,created,ticks,progress,retired,trash,automatic);}
        @Override public JsonObject stem(){return stem.deepCopy();}
        @Override public JsonObject gameplay(){return gameplay.deepCopy();}
        @Override public JsonObject progress(){return progress.deepCopy();}
        public Instance state(String value,long retired,String trash,boolean automatic){return new Instance(uuid,dimension,source,policy,ownership,owner,ownerSystem,run,seed,stem,gameplay,value,created,ticks,progress,retired,trash,automatic);}
        public Instance purged(){return new Instance(uuid,dimension,source,policy,ownership,"",ownerSystem,"","0",new JsonObject(),new JsonObject(),"purged",created,ticks,new JsonObject(),retired,null,true);}
        public Instance progress(JsonObject next){return new Instance(uuid,dimension,source,policy,ownership,owner,ownerSystem,run,seed,stem,gameplay,status,created,ticks,next,retired,trash,automatic);}
        public Instance owner(justepicdimensions.teams.JEDTeamSystems.Key key){return new Instance(uuid,dimension,source,policy,ownership,key.id().toString(),key.system(),run,seed,stem,gameplay,status,created,ticks,progress,retired,trash,automatic);}
        public boolean active(){return status.equals("active")||status.equals("pending");}
        public boolean closed(){return progress.has("closed_after_exit")&&progress.get("closed_after_exit").getAsBoolean();}
        /** First occupancy arms disposable runs; first subsequent vacancy closes them permanently. */
        public Instance observeOccupancy(boolean occupied){
            if(closed()||occupied&&progress.has("entered")||!occupied&&!progress.has("entered"))return this;
            var next=progress();next.addProperty(occupied?"entered":"closed_after_exit",true);return progress(next);
        }
    }
    public record Group(UUID id,String name,Set<UUID> members,Set<UUID> managers){public Group{members=Set.copyOf(members);managers=Set.copyOf(managers);if(!members.containsAll(managers))throw new JEDDefinitionException("Group managers must be current members");}}
    private final Path file;
    public final Map<ResourceLocation,Instance> instances=new TreeMap<>();
    public final Map<UUID,Group> groups=new TreeMap<>();
    public final Map<String,UUID> teams=new TreeMap<>();
    private JEDInstanceStore(Path world){file=world==null?null:world.resolve("data/justepicdimensions/instances.json");}
    public static JEDInstanceStore read(Path world){
        var store=new JEDInstanceStore(world);if(store.file==null||!Files.exists(store.file))return store;
        try{
            var root=JEDJson.object(JEDJson.read(store.file),store.file.toString());JEDJson.keys(root,Set.of("format_version","instances","groups","teams"),store.file.toString());if(JEDJson.integer(root.get("format_version"),"instances format")!=1)throw new JEDDefinitionException("Unsupported instance ledger format");
            for(var entry:root.getAsJsonObject("instances").entrySet()){
                var j=JEDJson.object(entry.getValue(),entry.getKey());var id=JEDDimensionDefinition.id(entry.getKey(),"instance ledger");UUID uuid=UUID.fromString(j.get("uuid").getAsString());
                var value=new Instance(uuid,id,JEDDimensionDefinition.id(j.get("source").getAsString(),"source"),JEDDimensionDefinition.id(j.get("policy").getAsString(),"policy"),j.get("ownership").getAsString(),j.get("owner").getAsString(),j.has("owner_system")?j.get("owner_system").getAsString():"minecraft",j.get("run").getAsString(),j.get("seed").getAsString(),j.getAsJsonObject("stem"),j.getAsJsonObject("gameplay"),j.get("status").getAsString(),j.get("created").getAsLong(),j.get("ticks").getAsLong(),j.getAsJsonObject("progress"),j.get("retired").getAsLong(),j.has("trash")?j.get("trash").getAsString():null,j.get("automatic").getAsBoolean());
                if(!id.equals(ResourceLocation.fromNamespaceAndPath("justepicdimensions","instances/"+uuid)))throw new JEDDefinitionException("Instance identity mismatch: "+id);
                if(!Set.of("personal","team","group").contains(value.ownership())||!Set.of("pending","active","retiring","retired","purged").contains(value.status()))throw new JEDDefinitionException("Invalid instance record "+id);
                if(!value.status().equals("purged")){UUID.fromString(value.owner());if(!value.run().matches("[a-z0-9_-]{1,64}"))throw new JEDDefinitionException("Invalid saved instance run "+id);Long.parseLong(value.seed());}
                if(value.created()<0||value.ticks()<0||value.retired()<0)throw new JEDDefinitionException("Invalid instance timestamps "+id);
                if(value.trash()!=null)UUID.fromString(value.trash());
                store.instances.put(id,value);
            }
            for(var entry:root.getAsJsonObject("groups").entrySet()){var j=entry.getValue().getAsJsonObject();UUID id=UUID.fromString(entry.getKey());store.groups.put(id,new Group(id,j.get("name").getAsString(),uuids(j.getAsJsonArray("members")),uuids(j.getAsJsonArray("managers"))));}
            root.getAsJsonObject("teams").entrySet().forEach(entry->store.teams.put(entry.getKey(),UUID.fromString(entry.getValue().getAsString())));return store;
        }catch(RuntimeException error){throw new JEDDefinitionException("Cannot read instance ownership ledger "+store.file+"; refusing to expose managed worlds",error);}
    }
    private static Set<UUID> uuids(JsonArray raw){var result=new TreeSet<UUID>();for(var value:raw)result.add(UUID.fromString(value.getAsString()));return result;}
    public void save(){
        if(file==null)throw new JEDDefinitionException("Instance ownership requires a selected save");var root=new JsonObject();root.addProperty("format_version",1);var records=new JsonObject();
        instances.forEach((id,r)->{var j=new JsonObject();j.addProperty("uuid",r.uuid.toString());j.addProperty("source",r.source.toString());j.addProperty("policy",r.policy.toString());j.addProperty("ownership",r.ownership);j.addProperty("owner",r.owner);j.addProperty("owner_system",r.ownerSystem);j.addProperty("run",r.run);j.addProperty("seed",r.seed);j.add("stem",r.stem);j.add("gameplay",r.gameplay);j.addProperty("status",r.status);j.addProperty("created",r.created);j.addProperty("ticks",r.ticks);j.add("progress",r.progress);j.addProperty("retired",r.retired);if(r.trash!=null)j.addProperty("trash",r.trash);j.addProperty("automatic",r.automatic);records.add(id.toString(),j);});root.add("instances",records);
        var groupsJson=new JsonObject();groups.forEach((id,g)->{var j=new JsonObject();j.addProperty("name",g.name);var members=new JsonArray();new TreeSet<>(g.members).forEach(uuid->members.add(uuid.toString()));j.add("members",members);var managers=new JsonArray();new TreeSet<>(g.managers).forEach(uuid->managers.add(uuid.toString()));j.add("managers",managers);groupsJson.add(id.toString(),j);});root.add("groups",groupsJson);var teamsJson=new JsonObject();teams.forEach((name,id)->teamsJson.addProperty(name,id.toString()));root.add("teams",teamsJson);JEDDimensionFiles.writeDurable(file,root);
    }
    /** Ownership is save data, excluded from reusable definition copies. */
    public static JsonElement blueprint(JsonElement value){var copy=value.deepCopy();if(copy.isJsonObject()){var root=copy.getAsJsonObject();root.remove(MARKER);if(root.has("settings")&&root.get("settings").isJsonObject())root.getAsJsonObject("settings").remove(MARKER);}return copy;}
    public static ResourceLocation properties(ResourceLocation id){return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),JEDDimensionDefinition.DIRECTORY+"/"+id.getPath()+".json");}
    public static ResourceLocation stem(ResourceLocation id){return ResourceLocation.fromNamespaceAndPath(id.getNamespace(),"dimension/"+id.getPath()+".json");}
    /** Source configuration is resolved first. Managed native identity/seed are immutable. */
    public static void apply(Path world,Map<ResourceLocation,JEDSourceDocument> resources){
        var store=read(world);var catalogue=JEDWorldCatalogue.read(world);var policies=JEDInstancePolicy.decodeAll(resources);
        for(var r:store.instances.values()){
            if(r.status().equals("pending")&&!catalogue.creations().containsKey(r.dimension())&&(world==null||!PREPARING.getOrDefault(world.toAbsolutePath().normalize(),Set.of()).contains(r.dimension())))continue;
            var existing=resources.get(properties(r.dimension));if(existing!=null&&(!existing.value().isJsonObject()||!existing.value().getAsJsonObject().has(MARKER)))throw new JEDDefinitionException("Authored properties collide with managed instance "+r.dimension);
            if(!r.active()){if(existing!=null)resources.put(properties(r.dimension),new JEDSourceDocument(properties(r.purged(),null,null),"retired managed instance"));continue;}
            var policy=policies.get(r.policy);var source=resources.get(properties(r.source));
            // Missing policies/source disable admission; retain sufficient native data for safe recovery.
            JsonObject live=source==null?null:source.value().getAsJsonObject();var props=properties(r,policy,live);
            var old=resources.putIfAbsent(stem(r.dimension),new JEDSourceDocument(r.stem,"managed instance "+r.uuid));if(old!=null&&!old.value().equals(r.stem))throw new JEDDefinitionException("Managed instance native stem changed: "+r.dimension);
            resources.put(properties(r.dimension),new JEDSourceDocument(props,"managed instance "+r.uuid));
        }
        for(var entry:resources.entrySet())if(entry.getKey().getPath().startsWith(JEDDimensionDefinition.DIRECTORY+"/")&&entry.getValue().value().isJsonObject()&&entry.getValue().value().getAsJsonObject().has(MARKER)){
            var id=JEDRegistryResources.entry(entry.getKey(),JEDDimensionDefinition.DIRECTORY);if(!store.instances.containsKey(id))throw new JEDDefinitionException("Managed instance ledger is missing for "+id+"; restore its ownership data before starting");
        }
    }
    public static JsonObject properties(Instance r,JEDInstancePolicy policy,JsonObject live){
        var result=policy!=null&&!policy.snapshot()&&live!=null?live.deepCopy():r.gameplay();
        // Gameplay snapshots never freeze access or travel security.
        for(String security:List.of("access","travel")){result.remove(security);if(live!=null&&live.has(security))result.add(security,live.get(security).deepCopy());}
        result.remove("instances");result.addProperty("format_version",1);var marker=new JsonObject();marker.addProperty("uuid",r.uuid.toString());marker.addProperty("source",r.source.toString());marker.addProperty("policy",r.policy.toString());result.add(MARKER,marker);
        var generation=result.has("generation")?result.getAsJsonObject("generation"):new JsonObject();generation.addProperty("seed",r.seed);result.add("generation",generation);
        var lifecycle=result.has("lifecycle")?result.getAsJsonObject("lifecycle"):new JsonObject();if(!lifecycle.has("load_on_start"))lifecycle.addProperty("load_on_start",false);result.add("lifecycle",lifecycle);return result;
    }
}
