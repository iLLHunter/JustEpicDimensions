package justepicdimensions.dimension;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.*;
import justepicdimensions.definition.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Reviewed, recoverable removal of authored JSON. No method here touches dimension world files. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDDefinitionRemoval {
    private record Plan(String actor,ResourceLocation dimension,JEDDefinitionManager.State state,Object resources,JsonObject record) {}
    private static final class Owner {
        final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED definition removal IO").factory());
        final Map<String,Plan> plans=new HashMap<>();
        boolean stopping;
    }
    private static final Map<MinecraftServer,Owner> OWNERS=new IdentityHashMap<>();
    private static Owner owner(MinecraftServer server){return OWNERS.computeIfAbsent(server,s->new Owner());}
    private static String actor(CommandSourceStack source){return source.getEntity()==null?"console":source.getEntity().getUUID().toString();}
    private static void ready(CommandSourceStack source){
        if(!source.getServer().isSameThread())throw new IllegalStateException("Definition removal requires the server thread");
        if(!source.hasPermission(2)||source.getPlayer()!=null&&!source.getPlayer().hasPermissions(2))throw new JEDDefinitionException("Definition removal requires operator level 2");
        JEDDimensionManager.checkCreation(source.getServer());JEDDefinitionHooks.requireIdle(source.getServer());
    }
    private static Set<ResourceLocation> keys(ResourceLocation id){return Set.of(JEDLifecycleStore.stem(id),ResourceLocation.fromNamespaceAndPath(id.getNamespace(),JEDDimensionDefinition.DIRECTORY+"/"+id.getPath()+".json"));}
    private static String relative(ResourceLocation key){return "data/"+key.getNamespace()+"/"+key.getPath();}
    public static CompletableFuture<JsonObject> preview(CommandSourceStack source,ResourceLocation id){
        ready(source);if(id.equals(Level.OVERWORLD.location()))throw new JEDDefinitionException("The running Overworld cannot be unregistered or removed");
        var server=source.getServer();JEDDimensionLifecycle.requireAvailable(server,id);
        if(!server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM).containsKey(id))throw new JEDDefinitionException("Unknown dimension: "+id);
        var state=JEDDefinitionManager.state(server);var resources=(JEDDefinitionResources)server.getResourceManager();var lease=resources.lease();var owner=owner(server);
        return CompletableFuture.supplyAsync(()->{try(lease){
            var selection=selection(server,state);var prepared=JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selection);
            var record=capture(selection.activeSource(),selection.world(),id);
            record.addProperty("fingerprint",prepared.fingerprint());return record;
        }catch(Exception error){throw new CompletionException(error);}},owner.worker).thenApplyAsync(record->{
            ready(source);if(owner.stopping||server.getResourceManager()!=resources||JEDDefinitionManager.state(server)!=state)throw new JEDDefinitionException("Definition removal preview became stale; preview again");
            owner.plans.entrySet().removeIf(e->e.getValue().state!=state||e.getValue().actor.equals(actor(source))&&e.getValue().dimension.equals(id));
            owner.plans.put(record.get("token").getAsString(),new Plan(actor(source),id,state,resources,record.deepCopy()));return record.deepCopy();
        },server);
    }
    private static JEDConfigRepository.Selection selection(MinecraftServer server,JEDDefinitionManager.State state){
        var value=JEDConfigRepository.select(server.getWorldPath(LevelResource.ROOT));
        if(!value.copies().isEmpty()||!value.activeSource().equals(state.prepared().selection().activeSource()))throw new JEDDefinitionException("Configuration selection/copy changed; reload resources and preview again");
        return value;
    }
    public static CompletableFuture<Void> confirm(CommandSourceStack source,ResourceLocation id,String token,boolean relocate){
        ready(source);var server=source.getServer();var owner=owner(server);var plan=owner.plans.get(token);
        if(plan==null||!plan.actor.equals(actor(source))||!plan.dimension.equals(id))throw new JEDDefinitionException("Unknown removal review token for this actor/dimension; preview first");
        if(plan.state!=JEDDefinitionManager.state(server)||plan.resources!=server.getResourceManager())throw new JEDDefinitionException("Definition revision changed; preview again");
        var resources=(JEDDefinitionResources)plan.resources;var lease=resources.lease();
        return CompletableFuture.supplyAsync(()->{try(lease){
            var selected=selection(server,plan.state);var prepared=JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selected);
            if(!prepared.fingerprint().equals(plan.record.get("fingerprint").getAsString()))throw new JEDDefinitionException("Definition inputs changed since preview; preview again");
            validate(server.getWorldPath(LevelResource.ROOT),plan.record,false);return true;
        }catch(Exception error){throw new CompletionException(error);}},owner.worker).thenComposeAsync(ignored->{
            ready(source);if(owner.stopping||plan.state!=JEDDefinitionManager.state(server)||plan.resources!=server.getResourceManager())throw new JEDDefinitionException("Definition removal became stale; preview again");
            owner.plans.remove(token);return JEDDimensionLifecycle.removeDefinition(server,id,relocate,plan.record);
        },server);
    }
    public static JsonObject capture(Path root,Path world,ResourceLocation id){
        root=root.toAbsolutePath().normalize();requireRoot(world,root);
        try(var ignored=JEDBuilderFiles.guard(root)){
            JEDBuilderFiles.recover(root);var files=scan(root);requireNoDependents(files,id);
            var record=new JsonObject();record.addProperty("format_version",1);record.addProperty("token",UUID.randomUUID().toString());record.addProperty("dimension",id.toString());record.addProperty("root",root.toString());
            record.addProperty("global",root.equals(FMLPaths.CONFIGDIR.get().resolve("justepicdimensions").toAbsolutePath().normalize()));
            var stamps=new JsonObject();files.forEach((name,bytes)->stamps.addProperty(name,digest(bytes)));record.add("sources",stamps);
            var removed=new JsonObject();for(var key:keys(id)){String name=relative(key);byte[] bytes=files.get(name);var item=new JsonObject();item.addProperty("hash",bytes==null?"<missing>":digest(bytes));if(bytes!=null)item.addProperty("bytes",Base64.getEncoder().encodeToString(bytes));removed.add(name,item);}record.add("files",removed);return record;
        }
    }
    private static Map<String,byte[]> scan(Path root){
        var files=new TreeMap<String,byte[]>();Path data=root.resolve("data");if(!Files.exists(data))return files;
        try(var walk=Files.walk(data)){for(var path:walk.sorted().toList()){
            JEDBuilderFiles.safe(root,path);if(Files.isRegularFile(path)&&path.toString().endsWith(".json"))files.put(root.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }}catch(java.io.IOException error){throw new JEDDefinitionException("Cannot inspect definition removal inputs",error);}return files;
    }
    private static ResourceLocation key(String relative){var path=relative.substring("data/".length());int split=path.indexOf('/');if(split<1)throw new JEDDefinitionException("Invalid authored resource path "+relative);return JEDDimensionDefinition.id(path.substring(0,split)+":"+path.substring(split+1),relative);}
    private static void requireNoDependents(Map<String,byte[]> files,ResourceLocation id){
        var targets=keys(id);var dependents=new TreeSet<String>();
        files.forEach((name,bytes)->{if(targets.contains(key(name)))return;var element=JEDJson.read(new java.io.StringReader(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),name);if(!element.isJsonObject())return;var value=element.getAsJsonObject();
            if(!value.has(JEDBuilderSources.HEADER))return;var header=JEDJson.object(value.get(JEDBuilderSources.HEADER),name);
            if(header.has("mode")&&header.get("mode").getAsString().equals("inherit")&&header.has("source")&&targets.contains(JEDDimensionDefinition.id(header.get("source").getAsString(),name)))dependents.add(key(name).toString());
        });
        if(!dependents.isEmpty())throw new JEDDefinitionException("Cannot remove "+id+": inherited definitions depend on it: "+dependents+". Convert these to independent copies first.");
    }
    private static void requireRoot(Path world,Path root){
        var global=FMLPaths.CONFIGDIR.get().resolve("justepicdimensions").toAbsolutePath().normalize();var local=world.toAbsolutePath().normalize().resolve("serverconfig/justepicdimensions");
        if(!root.equals(global)&&!root.equals(local))throw new JEDDefinitionException("Removal root is not this save's global or per-world JED configuration: "+root);
        JEDDimensionFiles.requireSafe(root.getParent(),root,false);
    }
    private static Path root(Path world,JsonObject record){
        JEDJson.keys(record,Set.of("format_version","token","dimension","root","global","sources","files","fingerprint"),"definition removal");
        if(JEDJson.integer(record.get("format_version"),"definition removal")!=1)throw new JEDDefinitionException("Unsupported definition removal version");
        UUID.fromString(record.get("token").getAsString());var root=Path.of(record.get("root").getAsString()).toAbsolutePath().normalize();requireRoot(world,root);return root;
    }
    /** Recheck the entire reviewed source set, including newly added inheritance edges. */
    public static void validate(Path world,JsonObject record,boolean replay){
        Path root=root(world,record);try(var ignored=JEDBuilderFiles.guard(root)){validateLocked(root,record,replay);}
    }
    private static void validateLocked(Path root,JsonObject record,boolean replay){
        JEDBuilderFiles.recover(root);var id=JEDDimensionDefinition.id(record.get("dimension").getAsString(),"definition removal");
        var planned=record.getAsJsonObject("files");var allowed=new TreeSet<String>();keys(id).forEach(k->allowed.add(relative(k)));
        if(!planned.keySet().equals(allowed))throw new JEDDefinitionException("Removal journal does not name exactly this dimension's JSON files");
        var current=scan(root);requireNoDependents(current,id);var expected=record.getAsJsonObject("sources");
        var names=new TreeSet<>(current.keySet());names.addAll(expected.keySet());
        for(String name:names){String wanted=expected.has(name)?expected.get(name).getAsString():"<missing>";String actual=current.containsKey(name)?digest(current.get(name)):"<missing>";
            if(actual.equals(wanted))continue;
            Path backup=JEDBuilderFiles.file(root,"definition-backups/"+record.get("token").getAsString()+"/"+name);
            if(replay&&planned.has(name)&&actual.equals("<missing>")&&Files.isRegularFile(backup)&&JEDBuilderFiles.stamp(backup).equals(wanted))continue;
            throw new JEDDefinitionException("Definition removal found changed input "+JEDBuilderFiles.file(root,name)+"; no conflicting file was overwritten. Preview again, or restore the reviewed input for journal recovery.");
        }
        for(String name:allowed){var item=planned.getAsJsonObject(name);JEDJson.keys(item,Set.of("hash","bytes"),name);String hash=item.get("hash").getAsString();
            if(!hash.equals(expected.has(name)?expected.get(name).getAsString():"<missing>"))throw new JEDDefinitionException("Removal source hash mismatch: "+name);
            if(!hash.equals("<missing>")&&(!item.has("bytes")||!digest(Base64.getDecoder().decode(item.get("bytes").getAsString())).equals(hash)))throw new JEDDefinitionException("Removal backup hash mismatch: "+name);
        }
    }
    /** Called only after save/close, or during closed-world recovery. Idempotent across individual file removals. */
    public static void execute(Path world,JsonObject record){
        Path root=root(world,record);try(var ignored=JEDBuilderFiles.guard(root)){
            validateLocked(root,record,true);var token=record.get("token").getAsString();
            for(var entry:record.getAsJsonObject("files").entrySet()){
                var item=entry.getValue().getAsJsonObject();if(item.get("hash").getAsString().equals("<missing>"))continue;
                Path target=JEDBuilderFiles.file(root,entry.getKey()),backup=JEDBuilderFiles.file(root,"definition-backups/"+token+"/"+entry.getKey());
                if(Files.exists(backup)&&!JEDBuilderFiles.stamp(backup).equals(item.get("hash").getAsString()))throw new JEDDefinitionException("Conflicting definition backup: "+backup);
                if(!Files.exists(backup))writeBytes(backup,Base64.getDecoder().decode(item.get("bytes").getAsString()));
                // Verify immediately before deletion as external editors do not participate in our lock.
                if(Files.exists(target)){if(!JEDBuilderFiles.stamp(target).equals(item.get("hash").getAsString()))throw new JEDDefinitionException("Definition changed during removal: "+target);Files.delete(target);}
            }
            JEDDimensionFiles.writeDurable(JEDBuilderFiles.file(root,"definition-backups/"+token+"/entry.json"),record);
        }catch(java.io.IOException error){throw new JEDDefinitionException("Definition removal is journaled for recovery",error);}
    }
    private static void writeBytes(Path file,byte[] bytes)throws java.io.IOException{
        Files.createDirectories(file.getParent());Path stage=Files.createTempFile(file.getParent(),".jed-definition-",".tmp");
        try{try(var channel=java.nio.channels.FileChannel.open(stage,StandardOpenOption.WRITE)){var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            try{Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(stage,file);}
        }finally{Files.deleteIfExists(stage);}
    }
    private static String digest(byte[] bytes){return JEDDimensionSync.digest(bytes);}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){var owner=OWNERS.remove(event.getServer());if(owner!=null){owner.stopping=true;owner.plans.clear();owner.worker.shutdown();}}
    private JEDDefinitionRemoval(){}
}
