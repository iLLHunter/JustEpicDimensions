package justepicdimensions.notification;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import justepicdimensions.api.lifecycle.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;

/** Detached durable outbox. Calls are serialized across producer, dispatcher and closed-save workers. */
public final class JEDNotificationStore {
    public static final class Pending {
        public final JEDOperation operation; public final UUID initialSession,attemptSession;
        public final Set<ResourceLocation> consumers; public final boolean ready; public final String recovery;
        public final int round, observed, bridged; public final Set<ResourceLocation> acknowledged;
        public Pending(JEDOperation operation,UUID initialSession,Set<ResourceLocation> consumers,boolean ready,String recovery,
                       int round,int observed,int bridged,Set<ResourceLocation> acknowledged){
            this(operation,initialSession,consumers,ready,recovery,round,observed,bridged,acknowledged,null);
        }
        private Pending(JEDOperation operation,UUID initialSession,Set<ResourceLocation> consumers,boolean ready,String recovery,
                       int round,int observed,int bridged,Set<ResourceLocation> acknowledged,UUID attemptSession){
            this.operation=Objects.requireNonNull(operation);this.initialSession=Objects.requireNonNull(initialSession);this.consumers=Set.copyOf(consumers);
            this.attemptSession=attemptSession;
            this.ready=ready;this.recovery=Objects.requireNonNull(recovery);this.round=round;this.observed=observed;this.bridged=bridged;this.acknowledged=Set.copyOf(acknowledged);
            if(round<1||round>2||observed<0||observed>round||bridged<0||bridged>round||!consumers.containsAll(acknowledged))throw new JEDDefinitionException("Invalid JED notification receipts: "+operation.id());
        }
        Pending receipt(Set<ResourceLocation> a,int o,int b){return new Pending(operation,initialSession,consumers,ready,recovery,round,o,b,a,attemptSession);}
        Pending verified(){return round==2?this:new Pending(operation,initialSession,consumers,ready,recovery,2,0,0,Set.of(),attemptSession);}
        Pending ready(JEDOperation value){return new Pending(value,initialSession,consumers,true,recovery,round,observed,bridged,acknowledged,attemptSession);}
        Pending attempted(UUID session){var value=attemptSession!=null&&!attemptSession.equals(session)?verified():this;return new Pending(operation,initialSession,consumers,ready,recovery,value.round,value.observed,value.bridged,value.acknowledged,session);}
        public boolean complete(){return round==2&&observed==round&&bridged==round&&acknowledged.containsAll(consumers);}
    }
    public static Path root(Path world){return world.resolve("data/justepicdimensions/notifications");}
    private static Path file(Path world,UUID id){var file=root(world).resolve(id+".json");JEDDimensionFiles.requireSafe(world,file,false);return file;}
    public static synchronized List<Pending> readAll(Path world){
        var root=root(world);JEDDimensionFiles.requireSafe(world,root,false);if(!Files.exists(root))return List.of();
        try(var files=Files.list(root)){var result=new ArrayList<Pending>();for(var file:files.filter(f->f.getFileName().toString().endsWith(".json")).sorted().toList()){
            JEDDimensionFiles.requireSafe(world,file,false);var id=UUID.fromString(file.getFileName().toString().replace(".json",""));var record=parse(JEDJson.object(JEDJson.read(file),file.toString()));
            if(!record.operation.id().equals(id)||!file.getFileName().toString().equals(id+".json"))throw new JEDDefinitionException("Notification filename/operation ID mismatch: "+file);result.add(record);
        }return List.copyOf(result);}catch(java.io.IOException|IllegalArgumentException error){throw new JEDDefinitionException("Cannot read JED notification outbox",error);}
    }
    public static synchronized Pending enqueue(Path world,Pending value){
        var file=file(world,value.operation.id());if(Files.exists(file)){
            var old=parse(JEDJson.object(JEDJson.read(file),file.toString()));
            if(old.operation.kind()!=value.operation.kind()||!old.operation.dimensions().keySet().equals(value.operation.dimensions().keySet()))throw new JEDDefinitionException("Conflicting completion ID: "+value.operation.id());
            if(!old.ready&&value.ready){old=old.ready(value.operation);save(world,old);}return old;
        }
        save(world,value);return value;
    }
    public static synchronized Pending verification(Path world,UUID id){var value=read(world,id).verified();save(world,value);return value;}
    public static synchronized Pending attempt(Path world,UUID id,UUID session){var value=read(world,id).attempted(session);save(world,value);return value;}
    public static synchronized Pending ready(Path world,UUID id,JEDOperation operation){var value=read(world,id).ready(operation);save(world,value);return value;}
    public static synchronized Pending acknowledge(Path world,UUID id,int round,ResourceLocation consumer){
        var value=read(world,id);if(value.round!=round)throw new JEDDefinitionException("Expired notification acknowledgement: "+id);
        if(!value.consumers.contains(consumer))throw new JEDDefinitionException("Unassigned notification consumer: "+consumer);
        var a=new HashSet<>(value.acknowledged);a.add(consumer);value=value.receipt(a,value.observed,value.bridged);save(world,value);return value;
    }
    public static synchronized Pending observations(Path world,UUID id,int round,boolean observed,boolean bridged){
        var value=read(world,id);if(value.round!=round)throw new JEDDefinitionException("Expired observation receipt: "+id);
        value=value.receipt(value.acknowledged,observed?round:value.observed,bridged?round:value.bridged);save(world,value);return value;
    }
    public static synchronized void remove(Path world,UUID id){try{Files.deleteIfExists(file(world,id));}catch(java.io.IOException error){throw new JEDDefinitionException("Cannot retire JED completion "+id,error);}}
    public static synchronized Pending read(Path world,UUID id){var value=parse(JEDJson.object(JEDJson.read(file(world,id)),"notification "+id));if(!value.operation.id().equals(id))throw new JEDDefinitionException("Notification filename/operation ID mismatch: "+id);return value;}
    private static void save(Path world,Pending value){JEDDimensionFiles.writeDurable(file(world,value.operation.id()),json(value));}
    public static JsonObject json(Pending value){
        var root=new JsonObject();root.addProperty("format_version",1);root.addProperty("id",value.operation.id().toString());root.addProperty("kind",value.operation.kind().name());
        root.addProperty("completed_at",value.operation.completedAt().toString());root.addProperty("definition_revision",value.operation.definitionRevision());
        var dimensions=new JsonObject();value.operation.dimensions().forEach((id,state)->{var v=new JsonObject();v.addProperty("registered",state.registered());v.addProperty("available",state.available());v.addProperty("loaded",state.loaded());dimensions.add(id.toString(),v);});root.add("dimensions",dimensions);
        root.addProperty("initial_session",value.initialSession.toString());root.addProperty("ready",value.ready);root.addProperty("recovery",value.recovery);root.addProperty("round",value.round);root.addProperty("observed",value.observed);root.addProperty("bridged",value.bridged);
        if(value.attemptSession!=null)root.addProperty("attempt_session",value.attemptSession.toString());
        var consumers=new JsonArray();value.consumers.stream().sorted().forEach(id->consumers.add(id.toString()));root.add("consumers",consumers);
        var a=new JsonArray();value.acknowledged.stream().sorted().forEach(id->a.add(id.toString()));root.add("acknowledged",a);return root;
    }
    public static Pending parse(JsonObject value){
        JEDJson.keys(value,Set.of("format_version","id","kind","completed_at","definition_revision","dimensions","initial_session","attempt_session","ready","recovery","round","observed","bridged","consumers","acknowledged"),"notification");
        if(JEDJson.integer(value.get("format_version"),"notification.format_version")!=1)throw new JEDDefinitionException("Unsupported notification outbox version");
        var dimensions=new TreeMap<ResourceLocation,JEDOperation.DimensionState>();for(var entry:value.getAsJsonObject("dimensions").entrySet()){
            var state=entry.getValue().getAsJsonObject();JEDJson.keys(state,Set.of("registered","available","loaded"),"notification state");dimensions.put(ResourceLocation.parse(entry.getKey()),new JEDOperation.DimensionState(state.get("registered").getAsBoolean(),state.get("available").getAsBoolean(),state.get("loaded").getAsBoolean()));
        }
        var operation=new JEDOperation(UUID.fromString(value.get("id").getAsString()),JEDOperation.Kind.valueOf(value.get("kind").getAsString()),Instant.parse(value.get("completed_at").getAsString()),value.get("definition_revision").getAsLong(),dimensions);
        var consumers=new HashSet<ResourceLocation>();for(var id:value.getAsJsonArray("consumers"))if(!consumers.add(ResourceLocation.parse(id.getAsString())))throw new JEDDefinitionException("Repeated notification consumer");
        var a=new HashSet<ResourceLocation>();for(var id:value.getAsJsonArray("acknowledged"))a.add(ResourceLocation.parse(id.getAsString()));
        return new Pending(operation,UUID.fromString(value.get("initial_session").getAsString()),consumers,value.get("ready").getAsBoolean(),value.get("recovery").getAsString(),JEDJson.integer(value.get("round"),"notification.round"),JEDJson.integer(value.get("observed"),"notification.observed"),JEDJson.integer(value.get("bridged"),"notification.bridged"),a,value.has("attempt_session")?UUID.fromString(value.get("attempt_session").getAsString()):null);
    }
    private JEDNotificationStore(){}
}
