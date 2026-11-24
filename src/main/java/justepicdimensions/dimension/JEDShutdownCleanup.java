package justepicdimensions.dimension;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import justepicdimensions.definition.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.util.DirectoryLock;

/** Whole-save disposal runs only after the native level-storage lock has successfully closed. */
public final class JEDShutdownCleanup {
    public record Temporary(net.minecraft.resources.ResourceLocation id, Path template, net.minecraft.resources.ResourceLocation fallback, List<String> commands) {
        public Temporary { commands = List.copyOf(commands); }
    }
    private record Plan(Path world, List<Temporary> dimensions, boolean whole, List<String> commands, Set<net.minecraft.resources.ResourceLocation> protectedDimensions) {}
    private static final Map<MinecraftServer,Plan> ARMED = new ConcurrentHashMap<>();
    private static final Set<MinecraftServer> INHIBITED=ConcurrentHashMap.newKeySet();
    public static void inhibit(MinecraftServer server){INHIBITED.add(server);ARMED.remove(server);}
    public static void arm(MinecraftServer server, Path world, List<Temporary> dimensions, boolean whole, List<String> commands) {
        if (INHIBITED.contains(server)||JEDDimensionLifecycle.busy(server)) { LogUtils.getLogger().warn("JED temporary save cleanup postponed: lifecycle or notification IO is unfinished"); return; }
        ARMED.put(server,new Plan(world,List.copyOf(dimensions),whole,List.copyOf(commands),JEDDimensionLifecycle.protectedDimensions(server)));
    }
    public static void closed(MinecraftServer server) {
        if(INHIBITED.remove(server)){ARMED.remove(server);return;}
        var plan = ARMED.remove(server); if (plan == null) return; var world = plan.world();
        var information=JEDDefinitionManager.state(server).prepared().controls();
        Thread.ofPlatform().daemon(false).name("JED closed save cleanup").start(() -> {
            try {
                if (!plan.dimensions().isEmpty()) try (var lock = DirectoryLock.create(world)) {
                    for (var dimension : plan.dimensions()) resetClosed(world,dimension,plan.protectedDimensions());
                }
                if (!plan.whole()) return;
                if (JEDLifecycleStore.read(world).operation() != null) throw new JEDDefinitionException("A dimension cleanup still requires recovery");
                JEDDimensionFiles.playerReferences(world,Level.OVERWORLD.location(),Level.OVERWORLD.location(),false);
                var parent = world.getParent(); String token = UUID.randomUUID().toString(); var bin = parent.resolve(".jed-world-trash").resolve(token);
                JEDDimensionFiles.requireSafe(parent,world,false); JEDDimensionFiles.requireSafe(parent,bin,false); Files.createDirectories(bin);
                var intent = new JsonObject(); intent.addProperty("world",world.toString()); intent.addProperty("closed",true); intent.addProperty("token",token);
                var pending = new JsonArray(); JEDLifecycleStore.read(world).deferredCommands().forEach(pending::add);
                if (!plan.commands().isEmpty()) {var operation=commandIntent(Level.OVERWORLD.location(),plan.commands());operation.addProperty("token",token);pending.add(operation);}
                intent.add("pending_commands",pending); intent.addProperty("delivery",pending.isEmpty() ? "DONE" : "PENDING");
                var notifications=new JsonArray();
                justepicdimensions.notification.JEDNotificationStore.readAll(world).forEach(value->notifications.add(justepicdimensions.notification.JEDNotificationStore.json(value)));
                if(plan.whole())notifications.add(justepicdimensions.notification.JEDNotificationStore.json(justepicdimensions.notification.JEDNotifications.closedReset(Level.OVERWORLD.location(),UUID.fromString(token))));
                intent.add("pending_notifications",notifications);
                if(!notifications.isEmpty())intent.addProperty("delivery","PENDING");
                JEDDimensionFiles.writeDurable(world.resolve(".jed-closed-save.json"),intent);
                JEDDimensionFiles.writeDurable(bin.resolve("entry.json"),intent);
                dispose(world,bin);
                JEDInfoLog.info(information,"JED closed temporary save moved to recoverable trash: {}",bin);
            } catch (RuntimeException | java.io.IOException error) { LogUtils.getLogger().warn("JED temporary save was retained: {}",error.getMessage()); }
        });
    }
    private static JsonObject commandIntent(net.minecraft.resources.ResourceLocation id,List<String> commands) {
        var operation = new JsonObject(); operation.addProperty("token",UUID.randomUUID().toString()); operation.addProperty("dimension",id.toString());
        operation.addProperty("action","reset"); operation.addProperty("phase","FILES_DONE"); operation.addProperty("fallback","minecraft:overworld"); operation.addProperty("relocate",false);
        var values = new JsonArray(); commands.forEach(values::add); operation.add("commands",values); return operation;
    }
    private static void resetClosed(Path world,Temporary dimension,Set<net.minecraft.resources.ResourceLocation> protectedDimensions) {
        try {
            var store = JEDLifecycleStore.read(world); if (store.operation() != null) throw new JEDDefinitionException("An unfinished lifecycle operation requires recovery");
            JEDDimensionFiles.requireDisjoint(world,dimension.id(),protectedDimensions);
            JEDDimensionFiles.playerReferences(world,dimension.id(),dimension.fallback(),false); JEDDimensionFiles.validateTemplate(dimension.template());
            var operation = commandIntent(dimension.id(),dimension.commands()); operation.addProperty("phase","RESERVED");
            JEDDimensionFiles.protect(operation,protectedDimensions);
            if (dimension.template() != null) operation.addProperty("template",dimension.template().toAbsolutePath().normalize().toString());
            store = store.reserve(operation,null,Map.of()); store = JEDDimensionFiles.execute(world,store);
            justepicdimensions.notification.JEDNotifications.persist(world,justepicdimensions.notification.JEDNotifications.closedReset(dimension.id(),UUID.fromString(operation.get("token").getAsString())));
            store.deferOperation();
            JEDInfoLog.info(JEDConfigControls.read(JEDConfigRepository.select(world)),"JED closed temporary dimension {} reset; commands deferred until next startup",dimension.id());
        } catch (RuntimeException error) { LogUtils.getLogger().warn("JED closed temporary dimension {} retained/recoverable: {}",dimension.id(),error.getMessage()); }
    }
    /** Storage-access constructor bridge: recover before acquiring the next native session lock. */
    public static void beforeAccess(net.minecraft.world.level.storage.LevelStorageSource source, String name) { recover(source.getBaseDir().resolve(name).toAbsolutePath().normalize()); }
    public static void recover(Path world) {
        Path parent = world.getParent(), root = parent.resolve(".jed-world-trash"); JEDDimensionFiles.requireSafe(parent,root,false);
        if (!Files.isDirectory(root)) return;
        try (var entries = Files.list(root)) {
            for (var bin : entries.toList()) {
                JEDDimensionFiles.requireSafe(root,bin,false); Path record = bin.resolve("entry.json");
                JEDDimensionFiles.requireSafe(bin,record,false);
                if (!Files.isRegularFile(record)) continue;
                var intent = JEDJson.object(JEDJson.read(record),"temporary save cleanup");
                if (!world.toString().equals(intent.get("world").getAsString()) || !intent.get("closed").getAsBoolean()) continue;
                if (!Files.exists(bin.resolve("world"))) {
                    var marker = world.resolve(".jed-closed-save.json"); JEDDimensionFiles.requireSafe(world,marker,false);
                    if (!Files.isRegularFile(marker) || !JEDJson.read(marker).equals(intent)) {
                        LogUtils.getLogger().warn("JED stale closed-save recovery entry retained without touching a different generation: {}",bin); continue;
                    }
                    dispose(world,bin);
                }
                deliver(world,record,intent);
            }
        } catch (java.io.IOException error) { throw new JEDDefinitionException("Cannot recover closed temporary save",error); }
    }
    private static synchronized void deliver(Path world,Path record,JsonObject intent) throws java.io.IOException {
        if (!intent.has("delivery") || !intent.get("delivery").getAsString().equals("PENDING")) return;
        JEDDimensionFiles.requireSafe(world.getParent(),world,false);
        // The previous generation is safely in trash. Queue the commands before the next native
        // storage access; a crash between queue and delivery marker is deduplicated by token.
        try (var lock = DirectoryLock.create(world)) {
            var store = JEDLifecycleStore.read(world);
            if(intent.has("pending_notifications"))for(var pending:intent.getAsJsonArray("pending_notifications"))
                justepicdimensions.notification.JEDNotificationStore.enqueue(world,justepicdimensions.notification.JEDNotificationStore.parse(pending.getAsJsonObject()));
            for (var operation : intent.getAsJsonArray("pending_commands")) store = store.enqueueCommands(operation.getAsJsonObject());
            intent.addProperty("delivery","DONE"); JEDDimensionFiles.writeDurable(record,intent);
        }
    }
    private static synchronized void dispose(Path world, Path bin) throws java.io.IOException {
        JEDDimensionFiles.requireSafe(world.getParent(),world,false); JEDDimensionFiles.requireSafe(bin.getParent(),bin,false);
        if (!Files.exists(world)) return;
        if (DirectoryLock.isLocked(world)) throw new JEDDefinitionException("Temporary save is still locked: " + world);
        var intent = JEDJson.object(JEDJson.read(bin.resolve("entry.json")),"closed save intent");
        Path marker = world.resolve(".jed-closed-save.json"); JEDDimensionFiles.requireSafe(world,marker,false);
        if (!Files.isRegularFile(marker) || !intent.has("token") || !JEDJson.object(JEDJson.read(marker),"closed save marker").equals(intent))
            throw new JEDDefinitionException("Closed-save recovery marker differs; retaining " + world);
        // Hold the native session lock through the move. A separate server must not open the save
        // between a read-only lock check and disposal (renaming open files is allowed on Linux).
        try (var lock = DirectoryLock.create(world)) {
            // Windows forbids renaming this directory while our own locked file is open. Release
            // it immediately before rename there; a competing open handle makes the rename fail.
            if (net.minecraft.Util.getPlatform() == net.minecraft.Util.OS.WINDOWS) lock.close();
            try { Files.move(world,bin.resolve("world"),StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(world,bin.resolve("world")); }
        }
    }
    private JEDShutdownCleanup() {}
}
