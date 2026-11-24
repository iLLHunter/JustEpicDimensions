package justepicdimensions.dimension;

import java.util.IdentityHashMap;
import net.minecraft.server.MinecraftServer;

/** Actual native stopServer scope; never changes ordinary chunk unloading. */
public final class JEDShutdownSafety {
    private static final ThreadLocal<IdentityHashMap<MinecraftServer, Integer>> STOPS = new ThreadLocal<>();

    private JEDShutdownSafety() {}

    public static void enter(MinecraftServer server) {
        var stops = STOPS.get();
        if (stops == null) { stops = new IdentityHashMap<>(); STOPS.set(stops); }
        stops.merge(server, 1, Integer::sum);
    }

    public static boolean isStopping(MinecraftServer server) {
        var stops = STOPS.get();
        return stops != null && stops.containsKey(server);
    }

    public static void exit(MinecraftServer server) {
        var stops = STOPS.get();
        if (stops == null) return;
        var depth = stops.get(server);
        if (depth == null) return;
        if (depth == 1) stops.remove(server); else stops.put(server, depth - 1);
        if (stops.isEmpty()) STOPS.remove();
    }
}
