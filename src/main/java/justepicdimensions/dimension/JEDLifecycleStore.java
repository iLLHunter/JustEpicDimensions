package justepicdimensions.dimension;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;

/** Worker-owned durable lifecycle intent. Native registry storage never contains lifecycle state. */
public final class JEDLifecycleStore {
    private final Path world;
    private final JsonObject value;
    private JEDLifecycleStore(Path world, JsonObject value) { this.world = world; this.value = value; }
    public static JEDLifecycleStore read(Path world) {
        var root = new JsonObject(); root.addProperty("format_version", 1); root.add("disabled", new JsonObject()); root.add("relocations", new JsonObject()); root.add("deferred_commands",new JsonArray());
        if (world != null && Files.exists(file(world))) {
            root = JEDJson.object(JEDJson.read(file(world)), "JED lifecycle journal");
            JEDJson.keys(root, Set.of("format_version", "disabled", "relocations", "operation", "deferred_commands", "scheduled_completions"), "JED lifecycle journal");
            if (!root.has("deferred_commands")) root.add("deferred_commands",new JsonArray());
            if (JEDJson.integer(root.get("format_version"), "lifecycle.format_version") != 1) throw new JEDDefinitionException("Unsupported lifecycle journal version");
            for (var entry : root.getAsJsonObject("disabled").entrySet()) JEDDimensionDefinition.id(entry.getKey(), "lifecycle.disabled");
            if (root.getAsJsonObject("disabled").has("minecraft:overworld")) throw new JEDDefinitionException("Lifecycle journal cannot disable the Overworld");
            if(root.has("scheduled_completions"))for(var entry:JEDJson.object(root.get("scheduled_completions"),"scheduled completions").entrySet())justepicdimensions.schedule.JEDScheduleStore.validateCompletion(entry.getKey(),JEDJson.object(entry.getValue(),"scheduled completion"));
            if (root.has("operation")) {
                var operation = JEDJson.object(root.get("operation"),"lifecycle.operation");
                if (!Set.of("unregister","reset","delete").contains(JEDJson.string(operation.get("action"),"lifecycle.action"))
                    || !Set.of("RESERVED","PREPARED","TRASHED","FILES_DONE","COMMANDS_CLAIMED").contains(JEDJson.string(operation.get("phase"),"lifecycle.phase")))
                    throw new JEDDefinitionException("Invalid lifecycle operation action or phase");
                JEDDimensionDefinition.id(JEDJson.string(operation.get("dimension"),"lifecycle.dimension"),"lifecycle.dimension");
                JEDDimensionFiles.trash(world, JEDJson.string(operation.get("token"),"lifecycle.token"));
                if(operation.has("schedule"))justepicdimensions.schedule.JEDScheduleStore.validateMetadata(JEDJson.object(operation.get("schedule"),"scheduled lifecycle metadata"),ResourceLocation.parse(operation.get("dimension").getAsString()));
            }
        }
        return new JEDLifecycleStore(world == null ? null : world.toAbsolutePath().normalize(), root.deepCopy());
    }
    public static Path file(Path world) { return world.resolve("data/justepicdimensions/dimension_lifecycle.json"); }
    public Set<ResourceLocation> disabled() {
        var result = new TreeSet<ResourceLocation>(); value.getAsJsonObject("disabled").keySet().forEach(id -> result.add(ResourceLocation.parse(id))); return Set.copyOf(result);
    }
    public JsonObject disabledEntry(ResourceLocation id) {
        var entry = value.getAsJsonObject("disabled").getAsJsonObject(id.toString()); return entry == null ? null : entry.deepCopy();
    }
    public Set<ResourceLocation> removedDefinitions(){
        var result=new TreeSet<ResourceLocation>();value.getAsJsonObject("disabled").entrySet().forEach(entry->{var v=entry.getValue().getAsJsonObject();if(v.has("definition_removed")&&v.get("definition_removed").getAsBoolean())result.add(ResourceLocation.parse(entry.getKey()));});return Set.copyOf(result);
    }
    public Map<ResourceLocation, JEDSourceDocument> retainedStems() {
        var result = new TreeMap<ResourceLocation,JEDSourceDocument>();
        value.getAsJsonObject("disabled").entrySet().forEach(entry -> {
            var id = ResourceLocation.parse(entry.getKey()); var record = entry.getValue().getAsJsonObject();
            result.put(stem(id), new JEDSourceDocument(record.get("stem"), "reserved lifecycle definition " + id));
        }); return Map.copyOf(result);
    }
    public static ResourceLocation stem(ResourceLocation id) { return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "dimension/" + id.getPath() + ".json"); }
    public JsonObject relocation(UUID player) {
        var entry = value.getAsJsonObject("relocations").getAsJsonObject(player.toString()); return entry == null ? null : entry.deepCopy();
    }
    public JsonObject relocation(UUID player, ResourceLocation dimension) {
        var entries = relocation(player); if (entries == null) return null;
        // Early development journals stored a single route per player.
        if (entries.has("from")) return entries.get("from").getAsString().equals(dimension.toString()) ? entries : null;
        return entries.getAsJsonObject(dimension.toString());
    }
    public JsonObject operation() { return value.has("operation") ? value.getAsJsonObject("operation").deepCopy() : null; }
    public List<JsonObject> deferredCommands() { return value.getAsJsonArray("deferred_commands").asList().stream().map(entry -> entry.getAsJsonObject().deepCopy()).toList(); }
    public boolean pending() { return value.has("operation") || !value.getAsJsonArray("deferred_commands").isEmpty(); }
    public JEDLifecycleStore deferOperation() {
        var next = copy(); var operation = next.operation();
        if (operation == null || !operation.get("phase").getAsString().equals("FILES_DONE")) throw new JEDDefinitionException("Only completed file work may defer its commands");
        if (!operation.getAsJsonArray("commands").isEmpty()||operation.has("schedule")) next.value.getAsJsonArray("deferred_commands").add(operation);
        next.value.remove("operation"); return next.save();
    }
    public JEDLifecycleStore enqueueCommands(JsonObject operation) {
        if (!Set.of("FILES_DONE","COMMANDS_CLAIMED").contains(operation.get("phase").getAsString())) throw new JEDDefinitionException("Deferred file work is not complete");
        String token = operation.get("token").getAsString();
        if (deferredCommands().stream().anyMatch(entry -> entry.get("token").getAsString().equals(token)) || value.has("operation") && value.getAsJsonObject("operation").get("token").getAsString().equals(token)) return this;
        var next = copy(); next.value.getAsJsonArray("deferred_commands").add(operation.deepCopy()); return next.save();
    }
    public JEDLifecycleStore promoteCommands() {
        if (value.has("operation")) return this;
        var next = copy(); var queued = next.value.getAsJsonArray("deferred_commands");
        if (!queued.isEmpty()) next.value.add("operation",queued.remove(0)); return next.save();
    }
    public JEDLifecycleStore reserve(JsonObject operation, JsonObject disabledEntry, Map<UUID,JsonObject> relocations) {
        if (value.has("operation")) throw new JEDDefinitionException("An unfinished lifecycle transaction requires recovery");
        var next = copy(); var intent = operation.deepCopy();
        String id = operation.get("dimension").getAsString();
        var previous = value.getAsJsonObject("disabled").get(id); if (previous != null) intent.add("previous_disabled",previous.deepCopy());
        next.value.add("operation",intent);
        if (disabledEntry != null) next.value.getAsJsonObject("disabled").add(id, disabledEntry.deepCopy());
        relocations.forEach((uuid, record) -> {
            var routes = next.relocation(uuid); if (routes == null) routes = new JsonObject();
            if (routes.has("from")) { var old = routes; routes = new JsonObject(); routes.add(old.get("from").getAsString(),old); }
            String from = record.get("from").getAsString(); var old = routes.getAsJsonObject(from); var merged = record.deepCopy();
            if (old != null) for (String field : List.of("position","respawn")) merged.addProperty(field,old.get(field).getAsBoolean() || record.get(field).getAsBoolean());
            routes.add(from,merged); next.value.getAsJsonObject("relocations").add(uuid.toString(),routes);
        });
        return next.save();
    }
    public JEDLifecycleStore phase(String phase) {
        var next = copy(); next.value.getAsJsonObject("operation").addProperty("phase", phase); return next.save();
    }
    public JsonObject scheduledCompletion(String key){var completions=value.has("scheduled_completions")?value.getAsJsonObject("scheduled_completions"):null;return completions!=null&&completions.has(key)?completions.getAsJsonObject(key).deepCopy():null;}
    /** Atomically retain the last receipt per scheduled job while clearing its native transaction. */
    public JEDLifecycleStore completeScheduled(JsonObject receipt){
        var operation=operation();if(operation==null||!operation.has("schedule")||!operation.get("token").equals(receipt.get("token"))||!operation.get("schedule").equals(receipt.get("schedule")))throw new JEDDefinitionException("Scheduled completion does not match the active native transaction");
        var next=copy();if(!next.value.has("scheduled_completions"))next.value.add("scheduled_completions",new JsonObject());
        for(var raw:operation.getAsJsonObject("schedule").getAsJsonArray("jobs")){
            var job=raw.getAsJsonObject();String key=job.get("key").getAsString();var completed=new JsonObject();for(String field:List.of("token","dimension","completed_at","completed_ticks"))completed.add(field,receipt.get(field).deepCopy());completed.add("due",job.get("due").deepCopy());completed.add("clock",job.get("clock").deepCopy());justepicdimensions.schedule.JEDScheduleStore.validateCompletion(key,completed);next.value.getAsJsonObject("scheduled_completions").add(key,completed);
        }
        next.value.remove("operation");return next.save();
    }
    public JEDLifecycleStore complete() { var next = copy(); next.value.remove("operation"); return next.save(); }
    public JEDLifecycleStore abortBeforeFiles() {
        var next = copy(); var operation = next.value.getAsJsonObject("operation"); String id = operation.get("dimension").getAsString();
        if (operation.has("previous_disabled")) next.value.getAsJsonObject("disabled").add(id,operation.get("previous_disabled").deepCopy());
        else next.value.getAsJsonObject("disabled").remove(id);
        if(operation.has("schedule")){
            if(!next.value.has("scheduled_completions"))next.value.add("scheduled_completions",new JsonObject());
            for(var raw:operation.getAsJsonObject("schedule").getAsJsonArray("jobs")){var job=raw.getAsJsonObject();var receipt=new JsonObject();receipt.add("token",operation.get("token").deepCopy());receipt.add("dimension",operation.get("dimension").deepCopy());receipt.add("due",job.get("due").deepCopy());receipt.add("clock",job.get("clock").deepCopy());receipt.addProperty("completed_at",System.currentTimeMillis());receipt.addProperty("completed_ticks",0L);receipt.addProperty("status","aborted");next.value.getAsJsonObject("scheduled_completions").add(job.get("key").getAsString(),receipt);}
        }
        next.value.remove("operation"); return next.save();
    }
    public JEDLifecycleStore activate(ResourceLocation id) {
        if (value.has("operation")) throw new JEDDefinitionException("Wait for lifecycle recovery before reactivation");
        var next = copy(); next.value.getAsJsonObject("disabled").remove(id.toString()); return next.save();
    }
    public JEDLifecycleStore forgetRelocation(UUID player) { var next = copy(); next.value.getAsJsonObject("relocations").remove(player.toString()); return next.save(); }
    private JEDLifecycleStore copy() { return new JEDLifecycleStore(world, value.deepCopy()); }
    private JEDLifecycleStore save() {
        if (world == null) throw new JEDDefinitionException("A selected save is required for lifecycle operations");
        JEDDimensionFiles.requireSafe(world, file(world), false); JEDDimensionFiles.writeDurable(file(world), value); return this;
    }
    /** Called before native registry decoding, while no ServerLevel can hold these files open. */
    public static void recoverFiles(Path world) {
        if (world == null) return;
        var store = read(world); var operation = store.operation(); if (operation == null) return;
        if (operation.get("phase").getAsString().equals("RESERVED") && operation.has("relocate") && !operation.get("relocate").getAsBoolean()) {
            try { JEDDimensionFiles.playerReferences(world,ResourceLocation.parse(operation.get("dimension").getAsString()),ResourceLocation.parse(operation.get("fallback").getAsString()),false); }
            catch (JEDDefinitionException error) {
                if(operation.has("definition_removal"))throw new JEDDefinitionException("Interrupted definition removal remains retired: saved player references require repair before recovery can continue",error);
                store.abortBeforeFiles(); com.mojang.logging.LogUtils.getLogger().warn("JED cancelled an interrupted pre-close operation to preserve saved players: {}",error.getMessage()); return;
            }
        }
        JEDDimensionFiles.execute(world, store);
    }
    public Path world() { return world; }
    private JEDLifecycleStore() { throw new AssertionError(); }
}
