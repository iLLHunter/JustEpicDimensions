package justepicdimensions.builder;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Background source preparation, bounded native validation, then explicit installation/publication. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDBuilderService {
    public enum Action { VALIDATE, PREVIEW, SAVE, APPLY, CREATE, LOAD }
    public record Outcome(String message,JsonObject preview,net.minecraft.network.chat.Component display){public Outcome(String message,JsonObject preview){this(message,preview,JEDText.fixed(message));}}
    private record Prepared(JEDPreparedDefinitions before,JEDPreparedDefinitions candidate,Map<ResourceLocation,JEDSourceDocument> edits,
                            Map<String,JsonElement> files,Map<String,String> stamps,String fingerprint){}
    private static final Map<MinecraftServer,Job> JOBS=new IdentityHashMap<>();
    public static boolean busy(MinecraftServer server){return JOBS.containsKey(server);}
    public static CompletableFuture<Outcome> pending(MinecraftServer server){var job=JOBS.get(server);if(job==null)throw new JEDDefinitionException("No builder operation is running");return job.result;}
    public static CompletableFuture<Outcome> run(CommandSourceStack source,String name,ResourceLocation target,Action action) {
        var server=source.getServer();if(!server.isSameThread())throw new IllegalStateException("Builder operations require the server thread");JEDDefinitionHooks.requireIdle(server);
        if(!source.hasPermission(2))throw new JEDDefinitionException("Builder operations require operator permission level 2");
        if(busy(server)||JEDDimensionManager.isBusy(server)||JEDDimensionCreation.preparing(server))throw new JEDDefinitionException("Wait for the current dimension/builder operation");
        var open=JEDBuilderStore.open(source,name);var job=new Job(source,open,target,action);JOBS.put(server,job);job.start();return job.result;
    }
    private static final class Job {
        final CommandSourceStack source;final MinecraftServer server;final JEDBuilderStore.Open open;final ResourceLocation target;final Action action;
        final JEDDefinitionManager.State base;final JEDDefinitionResources resources;final AutoCloseable lease;final boolean registered;
        final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("JED builder preparation").factory());
        final CompletableFuture<Outcome> result=new CompletableFuture<>(){
            @Override public boolean cancel(boolean interrupt){synchronized(Job.this){if(reserved)return false;boolean changed=super.cancel(false);if(changed){if(server.isSameThread())fail(new CancellationException("Builder operation cancelled"));else server.execute(()->fail(new CancellationException("Builder operation cancelled")));}return changed;}}
        };
        Prepared prepared;JEDRegistryTransaction transaction;final ArrayDeque<Runnable> validation=new ArrayDeque<>();
        volatile String expectedDraft;
        boolean validating;volatile boolean stopped,working;boolean leaseClosed,reserved;long maxSlice;int slices;
        Job(CommandSourceStack source,JEDBuilderStore.Open open,ResourceLocation target,Action action){this.source=source;server=source.getServer();this.open=open;expectedDraft=open.stamp();this.target=target;this.action=action;base=JEDDefinitionManager.state(server);resources=(JEDDefinitionResources)server.getResourceManager();lease=resources.lease();registered=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(target);}
        void check(){if(stopped||JOBS.get(server)!=this||JEDDefinitionManager.state(server)!=base||server.getResourceManager()!=resources)throw new JEDDefinitionException("Builder operation became stale after a resource/definition change; preview again");if(!expectedDraft.equals(JEDBuilderFiles.stamp(open.file())))throw new JEDDefinitionException("Draft changed while preparing; retry");}
        <T> void background(Callable<T> work,java.util.function.Consumer<T> next){working=true;CompletableFuture.supplyAsync(()->{try{if(stopped)throw new CancellationException("Builder stopped");return work.call();}catch(Exception error){throw new CompletionException(error);}},worker).whenComplete((value,error)->{working=false;if(stopped){releaseLease();server.execute(()->JOBS.remove(server,this));return;}server.execute(()->{if(stopped)return;try{check();if(error!=null)throw error;next.accept(value);}catch(Throwable failure){fail(failure);}});});}
        void start(){
            try{JEDBuilderRequirements.require(open.draft());}catch(Throwable error){fail(error);return;}
            boolean exists=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(target);
            if(action==Action.CREATE&&exists){fail(JEDText.failure("builder.exists",target));return;}
            if(action==Action.APPLY&&!exists){fail(JEDText.failure("builder.new_requires",target));return;}
            background(()->{
                var selection=JEDConfigRepository.select(base.prepared().selection().world());
                if(!selection.copies().isEmpty()||!selection.activeSource().equals(base.prepared().selection().activeSource()))throw new JEDDefinitionException("Configuration selection/copy changed; reload resources before using the builder");
                var edits=open.draft().materialize(target);if(!edits.containsKey(JEDBuilderDraft.resource("dimension",target)))throw new JEDDefinitionException("Draft needs a dimension document");
                var before=JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selection);
                var candidate=JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selection,null,edits);
                var files=new TreeMap<String,JsonElement>();var stamps=new TreeMap<String,String>();
                edits.forEach((key,value)->{String path="data/"+key.getNamespace()+"/"+key.getPath();files.put(path,value.value());stamps.put(path,JEDBuilderFiles.stamp(JEDBuilderFiles.file(selection.activeSource(),path)));});
                String fingerprint=before.fingerprint();
                if(!fingerprint.equals(JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selection).fingerprint()))throw new JEDDefinitionException("Configuration changed while preparing; retry");
                return new Prepared(before,candidate,Map.copyOf(edits),Map.copyOf(files),Map.copyOf(stamps),fingerprint);
            },value->{prepared=value;planValidation();});
        }
        void planValidation(){
            var verification=new TreeMap<ResourceLocation,JEDSourceDocument>();
            for(var entry:prepared.candidate.resources().entrySet()) {String directory=JEDRegistryResources.directory(entry.getKey());if(directory==null)continue;if(directory.equals("dimension")||!Objects.equals(prepared.candidate.nativeContents().get(entry.getKey()),base.prepared().nativeContents().get(entry.getKey())))verification.put(entry.getKey(),entry.getValue());}
            transaction=JEDRegistryTransaction.verification(server.registryAccess(),verification,base.prepared().nativeContents());
            validation.add(()->{
                if(!transaction.containsDimension(target))throw JEDText.failure("builder.target_missing",target);
                var definitions=prepared.candidate.validateBehavior(transaction.ops(),transaction::containsDimension);
                for(var definition:definitions.values())transaction.holder(Registries.LEVEL_STEM,definition.id()).filter(net.minecraft.core.Holder.Reference::isBound).ifPresent(holder->justepicdimensions.spawn.JEDSpawnController.validate(definition,holder.value().type().value()));
                var targetDefinition=definitions.get(target);if(targetDefinition!=null&&targetDefinition.spawnStructure()!=null)prepared.candidate.structureAssets().get(targetDefinition.spawnStructure().resource());
                justepicdimensions.world.JEDWorldControl.validate(server,prepared.candidate,definitions);
                justepicdimensions.world.JEDBorderController.validate(server,prepared.candidate,definitions);
                justepicdimensions.player.JEDPlayerStateController.validate(server,prepared.candidate);
            });validating=true;
        }
        void tick(){if(stopped||!validating)return;try{check();long start=System.nanoTime();do{if(!transaction.prepared())transaction.advance();else if(!validation.isEmpty())validation.remove().run();else{validating=false;validated();break;}}while(System.nanoTime()-start<2_000_000);maxSlice=Math.max(maxSlice,System.nanoTime()-start);slices++;}catch(Throwable error){fail(error);}}
        void validated(){synchronized(this){if(result.isCancelled())throw new CancellationException("Builder cancelled");reserved=action!=Action.PREVIEW&&action!=Action.VALIDATE;}background(()->{
            var selection=prepared.before.selection();if(!prepared.fingerprint.equals(JEDPreparedDefinitions.prepare(resources.rawDatapacks(),selection).fingerprint()))throw new JEDDefinitionException("Configuration changed during validation; preview again");
            var review=new JsonObject();review.addProperty("target",target.toString());review.addProperty("source_directory",selection.activeSource().toString());review.addProperty("fingerprint",prepared.fingerprint);review.addProperty("revision",base.revision());review.addProperty("draft",JEDJson.canonical(open.draft().content()).toString());
            review.addProperty("copy_mode",open.draft().mode());if(open.draft().source()!=null)review.addProperty("copy_source",open.draft().source().toString());
            var stamps=new JsonObject();prepared.stamps.forEach(stamps::addProperty);review.add("stamps",stamps);
            var changes=new JsonArray();
            for(var entry:prepared.edits.entrySet()) {var value=new JsonObject();value.addProperty("file",JEDBuilderFiles.file(selection.activeSource(),"data/"+entry.getKey().getNamespace()+"/"+entry.getKey().getPath()).toString());var old=prepared.before.resources().get(entry.getKey());value.add("before",old==null?JsonNull.INSTANCE:old.value());value.add("after",prepared.candidate.resources().get(entry.getKey()).value());value.addProperty("activation",JEDRegistryResources.directory(entry.getKey())!=null?(old==null?"create":"restart if changed"):"resource reload; initial/on_load values keep their configured timing");changes.add(value);}
            review.add("changes",changes);review.addProperty("registered",registered);var notices=new JsonArray();prepared.candidate.controlNotices().forEach(notices::add);review.add("notices",notices);
            if(action==Action.PREVIEW){open.draft().reviewed(review);open.save();expectedDraft=JEDBuilderFiles.stamp(open.file());return review;}
            if(action==Action.VALIDATE)return review;
            var previous=open.draft().review();
            if(previous==null||!previous.get("target").equals(review.get("target"))||!previous.get("source_directory").equals(review.get("source_directory"))||!previous.get("fingerprint").equals(review.get("fingerprint"))||!previous.get("stamps").equals(review.get("stamps"))||!previous.get("draft").equals(review.get("draft"))||previous.get("revision").getAsLong()!=base.revision())throw JEDText.failure("builder.preview_missing",open.file().getFileName().toString().replace(".json",""),target);
            // At this point the server owns the operation and reload/lifecycle entry points reject overlap.
            if(stopped)throw new CancellationException("Server stopped before builder save");
            String backup=JEDBuilderFiles.commit(selection.activeSource(),prepared.files,prepared.stamps);review.addProperty("backup",backup);
            // A successful write consumes the reviewed file stamps, even if activation later fails.
            open.draft().invalidateReview();open.save();expectedDraft=JEDBuilderFiles.stamp(open.file());return review;
        },review->{
            // Preview persisted its review without altering the editable content.
            if(action!=Action.PREVIEW)check();else if(JEDDefinitionManager.state(server)!=base)throw new JEDDefinitionException("Preview became stale; preview again");
            review.addProperty("validation_slices",slices);review.addProperty("maximum_validation_slice_ns",maxSlice);finishOwner();
            if(action==Action.APPLY||(action==Action.LOAD&&server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(target))) {
                activate(server.reloadResources(server.getPackRepository().getSelectedIds()),review,false);
            } else if(action==Action.CREATE||action==Action.LOAD) {
                try{activate(JEDDimensionManager.create(server,target),review,true);}catch(Throwable error){result.completeExceptionally(error);}
            } else result.complete(new Outcome(action==Action.SAVE?"Saved "+target+"; active dimensions are unchanged.":action==Action.PREVIEW?"Validated "+target+". Preview saved with destination files and activation requirements.":"Validated "+target+"; run preview before saving.",review,action==Action.SAVE?JEDText.tr("builder.saved",target):action==Action.PREVIEW?JEDText.tr("builder.preview_saved",target):JEDText.tr("builder.validated",target)));
        });}
        void activate(CompletableFuture<Void> future,JsonObject review,boolean creation){future.whenComplete((v,error)->server.execute(()->{try{if(error!=null)throw new JEDDefinitionException("Files saved; "+(creation?"creation":"application")+" failed: "+root(error).getMessage(),root(error),JEDText.tr("builder.files_failed",JEDText.tr(creation?"operation.creation":"operation.application"),JEDText.error(error)));if(action==Action.LOAD)JEDDimensionManager.load(server,target);result.complete(new Outcome("Saved and "+(creation?"created ":"applied ")+target+(action==Action.LOAD?"; level loaded":"")+". Pending restart: "+JEDDefinitionManager.state(server).prepared().pendingRestart(),review,JEDText.tr("builder.activated",JEDText.tr(creation?"builder.created":"builder.applied",target),action==Action.LOAD?JEDText.tr("builder.loaded"):net.minecraft.network.chat.Component.empty(),JEDDefinitionManager.state(server).prepared().pendingRestart())));}catch(Throwable failure){result.completeExceptionally(failure);}}));}
        synchronized void releaseLease(){if(leaseClosed)return;leaseClosed=true;try{lease.close();}catch(Exception error){com.mojang.logging.LogUtils.getLogger().warn("Could not release builder resource lease",error);}}
        void finishOwner(){worker.shutdown();if(!working){JOBS.remove(server,this);releaseLease();}}
        void fail(Throwable error){if(stopped)return;stopped=true;finishOwner();result.completeExceptionally(root(error));}
    }
    public static Throwable root(Throwable error){while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();return error;}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var job=JOBS.get(event.getServer());if(job!=null)job.tick();}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){var job=JOBS.get(event.getServer());if(job!=null)job.fail(new CancellationException("Server is stopping; builder operation cancelled"));}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){JOBS.remove(event.getServer());}
    public static void copy(CommandSourceStack source,String name,ResourceLocation id,String mode) {
        if(!source.hasPermission(2))throw new JEDDefinitionException("Builder operations require operator permission level 2");
        if(!mode.equals("independent")&&!mode.equals("inherit"))throw new JEDDefinitionException("Use independent or inherit");
        var server=source.getServer();var state=JEDDefinitionManager.state(server);var docs=new JsonObject();var fallback=new JsonObject();
        var authored=new TreeMap<ResourceLocation,JEDSourceDocument>();var root=state.prepared().selection().activeSource();
        try(var ignored=JEDBuilderFiles.guard(root)){JEDBuilderFiles.recover(root);for(String alias:List.of("dimension","properties")){var key=JEDBuilderDraft.resource(alias,id);var file=JEDBuilderFiles.file(root,"data/"+key.getNamespace()+"/"+key.getPath());if(Files.isRegularFile(file))authored.put(key,new JEDSourceDocument(JEDJson.read(file),"JSON "+file));}}
        for(String alias:List.of("dimension","properties")) {
            var key=JEDBuilderDraft.resource(alias,id);var effective=state.prepared().resources().get(key);JsonObject value;
            if(effective!=null)value=JEDJson.object(effective.value(),key.toString());
            else if(alias.equals("dimension")) {var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(id);if(stem==null)throw new JEDDefinitionException("Unknown dimension "+id);value=LevelStem.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),stem).getOrThrow(error->new JEDDefinitionException(error)).getAsJsonObject();}
            else {value=new JsonObject();value.addProperty("format_version",1);}
            if(alias.equals("properties"))value=justepicdimensions.instance.JEDInstanceStore.blueprint(value).getAsJsonObject();
            fallback.add(alias,value.deepCopy());
            if(mode.equals("inherit")) {value=authored.containsKey(key)?authored.get(key).value().getAsJsonObject():new JsonObject();value.remove(JEDBuilderSources.HEADER);if(alias.equals("properties"))value.addProperty("format_version",1);}
            docs.add(alias,value);
        }
        JEDBuilderStore.edit(source,name,draft->draft.copy(id,mode,docs,fallback));
    }
    public static JsonObject terrain(MinecraftServer server,ResourceLocation id){
        if(!server.isSameThread())throw new IllegalStateException("Terrain inspection requires the server thread");
        var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(id);
        if(stem==null)throw JEDText.failure("justepicdimensions.builder.terrain_unknown",id);
        return LevelStem.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,server.registryAccess()),stem).getOrThrow(error->new JEDDefinitionException(error)).getAsJsonObject();
    }
    private JEDBuilderService(){}
}
