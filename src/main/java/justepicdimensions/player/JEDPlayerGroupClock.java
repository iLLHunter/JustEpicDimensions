package justepicdimensions.player;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** A saved monotonic clock, independent of configurable world clocks and /time commands. */
@EventBusSubscriber(modid = "justepicdimensions")
public final class JEDPlayerGroupClock {
    private static final String KEY = "justepicdimensions_player_group_clock";
    private static final Map<MinecraftServer, Boolean> REQUESTED = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Counter> COUNTERS = new IdentityHashMap<>();

    public static void bind(MinecraftServer server, boolean needed) { REQUESTED.put(server, needed); }

    private static Counter counter(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Player-group clocks require the server thread");
        if (!COUNTERS.containsKey(server) || (COUNTERS.get(server) == null && REQUESTED.getOrDefault(server, false))) {
            var factory = new SavedData.Factory<>(Counter::new, Counter::read);
            var storage = server.overworld().getDataStorage();
            var counter = storage.get(factory, KEY);
            // Native storage logs load errors and returns null. Never mistake an unreadable clock for a new one.
            var file=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/"+KEY+".dat");
            if(counter==null&&java.nio.file.Files.exists(file))
                throw new IllegalStateException("Cannot load saved JED player-group clock " + file + "; existing data retained");
            if(counter==null&&REQUESTED.getOrDefault(server,false)){counter=new Counter();storage.set(KEY,counter);}
            COUNTERS.put(server, counter);
        }
        return COUNTERS.get(server);
    }

    public static long ticks(MinecraftServer server) {
        var counter = counter(server);
        return counter == null ? 0 : counter.ticks;
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!REQUESTED.containsKey(server) || server.overworld() == null) return;
        var counter = counter(server);
        // An existing clock continues even if its last group is temporarily removed from configuration.
        if (counter != null && counter.ticks < Long.MAX_VALUE) { counter.ticks++; counter.setDirty(); }
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        REQUESTED.remove(event.getServer()); COUNTERS.remove(event.getServer());
    }

    private static final class Counter extends SavedData {
        long ticks;
        static Counter read(CompoundTag tag, HolderLookup.Provider registries) {
            if (tag.getInt("format_version") != 1 || !tag.contains("ticks", net.minecraft.nbt.Tag.TAG_LONG)
                    || tag.getLong("ticks") < 0)
                throw new IllegalStateException("Invalid saved JED player-group clock; retained data requires repair");
            var counter = new Counter(); counter.ticks = tag.getLong("ticks"); return counter;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("format_version", 1); tag.putLong("ticks", ticks); return tag;
        }
    }
    private JEDPlayerGroupClock() {}
}
