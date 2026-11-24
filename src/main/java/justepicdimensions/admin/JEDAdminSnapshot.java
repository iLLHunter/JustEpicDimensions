package justepicdimensions.admin;

import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.provider.JEDProviderController;
import justepicdimensions.travel.JEDTeleportService;
import justepicdimensions.world.JEDLevelAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/** Server-thread snapshots only. No getLevel(), native codecs, file reads or chunk/entity enumeration. */
public final class JEDAdminSnapshot {
    private static final Gson JSON=new GsonBuilder().registerTypeAdapter(ResourceLocation.class,(JsonSerializer<ResourceLocation>)(id,type,context)->new JsonPrimitive(id.toString())).create();
    public enum Filter { ALL, REGISTERED, LOADED, RETIRED }
    public record Row(ResourceLocation id,JsonObject data){public Row{data=data.deepCopy();}public JsonObject data(){return data.deepCopy();}}
    public record Image(JsonObject server,List<Row> dimensions){public Image{server=server.deepCopy();dimensions=List.copyOf(dimensions);}public JsonObject server(){return server.deepCopy();}public JsonObject json(){var out=server();var rows=new JsonArray();dimensions.forEach(row->rows.add(row.data()));out.add("dimensions",rows);return out;}}
    private static final class View {
        final JEDDefinitionManager.State state;final Set<ResourceLocation> ids,retired;final Map<ResourceLocation,JEDSourceDocument> retained;
        View(MinecraftServer server){thread(server);state=JEDDefinitionManager.state(server);retired=JEDDimensionLifecycle.retiredDimensions(server);retained=JEDDimensionLifecycle.retainedStems(server);var all=new TreeSet<>(server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).keySet());server.levelKeys().forEach(key->all.add(key.location()));all.addAll(state.definitions().keySet());all.addAll(state.prepared().disabled());all.addAll(state.prepared().configuredDisabled());all.addAll(retired);ids=Collections.unmodifiableSet(all);}
    }
    public static void thread(MinecraftServer server){if(!server.isSameThread())throw new IllegalStateException("Administrative inspection requires the server thread");}
    public static Set<ResourceLocation> ids(MinecraftServer server){
        return new View(server).ids;
    }
    private static List<ResourceLocation> filtered(MinecraftServer server,View view,Filter filter){return view.ids.stream().filter(id->switch(filter){case ALL->true;case REGISTERED->server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).containsKey(id);case LOADED->server.levelKeys().contains(ResourceKey.create(Registries.DIMENSION,id));case RETIRED->view.retired.contains(id);}).toList();}
    public static List<Row> list(MinecraftServer server,Filter filter){var view=new View(server);return filtered(server,view,filter).stream().map(id->row(server,id,false,view)).toList();}
    public record Page(int number,int pages,int total,List<Row> rows){public Page{rows=List.copyOf(rows);}}
    public static Page page(MinecraftServer server,Filter filter,int number){return page(server,filter,number,8);}
    public static Page page(MinecraftServer server,Filter filter,int number,int size){if(size<1||size>32)throw new JEDDefinitionException("Page size must be between 1 and 32");var view=new View(server);var ids=filtered(server,view,filter);int pages=Math.max(1,(ids.size()+size-1)/size);if(number<1||number>pages)throw new JEDDefinitionException("Page must be between 1 and "+pages);return new Page(number,pages,ids.size(),ids.stream().skip((long)(number-1)*size).limit(size).map(id->row(server,id,false,view)).toList());}
    private static JsonObject header(MinecraftServer server){
        thread(server);var state=JEDDefinitionManager.state(server);var root=new JsonObject();root.addProperty("format_version",1);root.addProperty("captured_at",Instant.now().toString());root.addProperty("server_tick",server.getTickCount());root.addProperty("definition_revision",state.revision());root.addProperty("minecraft","1.21.1");root.addProperty("neoforge","21.1.243");root.addProperty("jed_version",net.neoforged.fml.ModList.get().getModContainerById("justepicdimensions").orElseThrow().getModInfo().getVersion().toString());
        root.addProperty("configuration_directory",state.prepared().selection().activeSource().toString());root.add("pending_restart",json(state.prepared().pendingRestart()));root.add("configuration_notices",json(state.prepared().controlNotices()));root.addProperty("creation_preparing",JEDDimensionCreation.preparing(server));root.addProperty("builder_busy",justepicdimensions.builder.JEDBuilderService.busy(server));root.addProperty("bulk_unload_busy",JEDAdminUnloader.busy(server));root.addProperty("registry_recovery_required",JEDDimensionManager.recoveryRequiredForInspection(server));root.add("lifecycle",JEDDimensionLifecycle.diagnostics(server));
        root.add("notifications",json(justepicdimensions.notification.JEDNotifications.diagnostics(server)));
        var categories=new JsonObject();for(var player:server.getPlayerList().getPlayers())categories.addProperty(player.getUUID().toString(),justepicdimensions.player.JEDPlayerGroupController.describe(player));root.add("player_category_groups",categories);
        var sync=new JsonObject();sync.addProperty("dimension_protocol",3);sync.addProperty("lighting_protocol",2);sync.addProperty("online_players",server.getPlayerList().getPlayerCount());sync.addProperty("ready_players",server.getPlayerList().getPlayers().stream().filter(JEDPlayerReadiness::isReady).count());root.add("synchronization",sync);
        JEDCreationTimings.last(server).ifPresent(report->root.add("last_creation_timings",json(report)));root.add("travel_timings",json(JEDTeleportService.timings(server)));
        root.add("bulk_unload",JEDAdminUnloader.diagnostics(server));JEDAdminUnloader.last(server).ifPresent(out->root.add("last_bulk_unload",json(out)));return root;
    }
    public static final class Capture {
        private final MinecraftServer server;private final View view;private final JsonObject root;private final Iterator<ResourceLocation> remaining;private final List<Row> rows=new ArrayList<>();
        public Capture(MinecraftServer server,ResourceLocation dimension){this.server=server;view=new View(server);root=header(server);if(dimension!=null&&!view.ids.contains(dimension))throw new JEDDefinitionException("Unknown dimension: "+dimension);remaining=(dimension==null?view.ids:List.of(dimension)).iterator();}
        public boolean advance(){thread(server);if(JEDDefinitionManager.state(server)!=view.state)throw new JEDDefinitionException("Definitions changed while capturing the report; retry");if(!remaining.hasNext())return false;rows.add(row(server,remaining.next(),true,view));return true;}
        public Image finish(){thread(server);if(remaining.hasNext())throw new IllegalStateException("Report capture incomplete");root.addProperty("last_server_tick",server.getTickCount());return new Image(root,rows);}
    }
    public static Image capture(MinecraftServer server,ResourceLocation dimension){var work=new Capture(server,dimension);while(work.advance()){}return work.finish();}
    public static Row row(MinecraftServer server,ResourceLocation id,boolean detail){return row(server,id,detail,new View(server));}
    private static Row row(MinecraftServer server,ResourceLocation id,boolean detail,View view){
        thread(server);if(!view.ids.contains(id))throw new JEDDefinitionException("Unknown dimension: "+id);
        var state=view.state;var definition=state.definitions().get(id);var prepared=state.prepared();var stem=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(id);var level=server.levels.get(ResourceKey.create(Registries.DIMENSION,id));
        var row=new JsonObject();row.addProperty("captured_server_tick",server.getTickCount());row.addProperty("id",id.toString());row.addProperty("display_name",JEDTeleportService.name(server,id).getString());row.addProperty("registered",stem!=null);row.addProperty("loaded",level!=null);row.addProperty("retired",view.retired.contains(id));row.addProperty("configured_disabled",prepared.configuredDisabled().contains(id));row.addProperty("available",stem!=null&&JEDDimensionLifecycle.available(server,id));
        var raw=detail||stem==null?prepared.resources().get(JEDLifecycleStore.stem(id)):null;if(raw==null&&(detail||stem==null))raw=view.retained.get(JEDLifecycleStore.stem(id));if(raw==null&&(detail||stem==null))raw=prepared.origins().originals().get(JEDLifecycleStore.stem(id));
        row.addProperty("type",level==null?"<unavailable>":level.dimensionTypeRegistration().unwrapKey().map(key->key.location().toString()).orElse("<inline>"));
        if(stem!=null){row.addProperty("type",stem.type().unwrapKey().map(key->key.location().toString()).orElse("<inline>"));row.addProperty("generator_class",stem.generator().getClass().getName());row.addProperty("biome_source_class",stem.generator().getBiomeSource().getClass().getName());}else if(raw!=null&&raw.value().getAsJsonObject().has("type"))row.add("type",raw.value().getAsJsonObject().get("type"));
        row.addProperty("temporary",definition!=null&&definition.lifecycle().temporary());row.addProperty("keep_loaded",definition!=null&&definition.lifecycle().keepLoaded());row.addProperty("load_on_start",stem!=null&&JEDDimensionManager.loadAtStartup(server,ResourceKey.create(Registries.LEVEL_STEM,id)));
        row.add("monitoring",justepicdimensions.monitor.JEDMonitoringController.diagnostics(server,id));
        if(level!=null)row.add("spawn_chunks",justepicdimensions.spawn.JEDSpawnChunks.diagnostics(level));
        row.addProperty("provider",definition!=null&&definition.provider()!=null?definition.provider().type().id().toString():"native");String blocker=JEDAdminUnloader.reason(server,id,false);row.addProperty("unload_blocker",blocker==null?"none":blocker);
        if(level!=null){var chunks=level.getChunkSource();var distance=chunks.chunkMap.getDistanceManager();row.addProperty("players",level.players().size());row.addProperty("loaded_chunks",chunks.getLoadedChunksCount());row.addProperty("loaded_entities",level.entityManager.count());row.addProperty("forced_chunks",level.getForcedChunks().size());row.addProperty("ticket_chunks",distance.tickets.size());row.addProperty("pending_chunk_tasks",chunks.getPendingTasksCount());}
        var blockers=new JsonArray();if(id.equals(Level.OVERWORLD.location()))blockers.add("overworld");if(row.get("temporary").getAsBoolean())blockers.add("temporary: include-temporary required for bulk reset");if(row.get("keep_loaded").getAsBoolean())blockers.add("keep_loaded");if(JEDDimensionLifecycle.pinned(server,id))blockers.add(JEDDimensionLifecycle.pinReason(server,id));if(!JEDDimensionLifecycle.available(server,id))blockers.add("disabled or completing lifecycle work");if(level==null)blockers.add("not loaded");else{if(!level.players().isEmpty())blockers.add("players");if(!level.getForcedChunks().isEmpty())blockers.add("forced chunks");if(level.getChunkSource().chunkMap.getDistanceManager().hasTickets())blockers.add("chunk tickets");}row.add("unload_blockers",blockers);
        if(detail){row.add("pending_restart",json(prepared.pendingRestart()));if(raw!=null){row.add("native_definition",raw.value());row.add("native_origins",json(raw.origins()));}if(definition!=null){row.add("properties",definition.source().value());row.add("property_origins",json(definition.source().origins()));row.addProperty("client_light_check",state.lightPolicies().containsKey(id)?state.lightPolicies().get(id).id():"unmanaged");}
            if(level!=null){var data=((JEDLevelAccess)level).jed$context();var nativeData=data.data();var world=new JsonObject();world.addProperty("independent",data.independent());world.addProperty("level_data_class",nativeData.getClass().getName());world.addProperty("level_class",level.getClass().getName());world.addProperty("chunk_source_class",level.getChunkSource().getClass().getName());var provider=JEDProviderController.policy(level);if(provider!=null){world.addProperty("provider_class",provider.getClass().getName());world.addProperty("provider_profile",provider.profile().name().toLowerCase(Locale.ROOT));world.add("provider_settings",provider.settings().json());}world.addProperty("seed",level.getSeed());world.addProperty("game_time",level.getGameTime());world.addProperty("day_time",level.getDayTime());world.addProperty("difficulty",level.getDifficulty().name().toLowerCase(Locale.ROOT));world.addProperty("difficulty_locked",nativeData.isDifficultyLocked());world.addProperty("hardcore",nativeData.isHardcore());world.addProperty("game_mode",nativeData.getGameType().getName());world.addProperty("allow_commands",nativeData.isAllowCommands());world.addProperty("raining",level.isRaining());world.addProperty("thundering",level.isThundering());world.addProperty("spawn",level.getSharedSpawnPos().toShortString());world.add("gamerules",NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE,level.getGameRules().createTag()));var border=new JsonObject();border.addProperty("center_x",level.getWorldBorder().getCenterX());border.addProperty("center_z",level.getWorldBorder().getCenterZ());border.addProperty("size",level.getWorldBorder().getSize());border.addProperty("target_size",level.getWorldBorder().getLerpTarget());world.add("border",border);row.add("world_state",world);}
        }return new Row(id,row);
    }
    private static JsonElement json(Object value){return JSON.toJsonTree(value);}
    private JEDAdminSnapshot(){}
}
