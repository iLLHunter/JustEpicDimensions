package justepicdimensions.dimension;

import java.util.Collections;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The single owner of runtime changes to the server's native level map.
 *
 * <p>JED's coremod hooks {@link MinecraftServer#getLevel}: it hides inaccessible JED dimensions and loads registered
 * ones on demand. JED code that needs the levels that are loaded right now must therefore read the map directly with
 * {@code server.levels.get(key)} (never {@code getLevel}, which recurses when called from the loader itself).
 * {@link MinecraftServer#levelKeys} and {@link MinecraftServer#getAllLevels} are not hooked and read the same map;
 * {@link #view} covers the remaining read-only map operations.
 * Writes go through {@link #register}, {@link #remove} and {@link #reorder} so the tick cache is always refreshed.
 * The map itself is reached through the access transformer entry for {@code MinecraftServer.levels}.</p>
 */
public final class JEDServerLevels {
    private JEDServerLevels() {}

    /** Read-only live view of the native level map, in the server's tick order. */
    public static Map<ResourceKey<Level>, ServerLevel> view(MinecraftServer server) {
        return Collections.unmodifiableMap(server.levels);
    }

    public static void register(MinecraftServer server, ResourceKey<Level> key, ServerLevel level) {
        synchronized (server) { server.levels.put(key, level); }
        refreshTickOrder(server);
    }

    public static void remove(MinecraftServer server, ResourceKey<Level> key) {
        synchronized (server) { server.levels.remove(key); }
        refreshTickOrder(server);
    }

    /** Replace the map's iteration (tick) order with {@code ordered}, which must contain exactly the loaded levels. */
    public static void reorder(MinecraftServer server, Iterable<ServerLevel> ordered) {
        synchronized (server) {
            server.levels.clear();
            for (var level : ordered) server.levels.put(level.dimension(), level);
        }
        refreshTickOrder(server);
    }

    /**
     * NeoForge caches the level array ticked each server tick and exposes {@code markWorldsDirty()} as the only way to
     * invalidate it. It is deprecated as "internal use only", not for removal, and has no public replacement; any mod
     * that adds or removes levels at runtime must call it. Keep this the only call site.
     */
    @SuppressWarnings("deprecation")
    private static void refreshTickOrder(MinecraftServer server) {
        server.markWorldsDirty();
    }
}
