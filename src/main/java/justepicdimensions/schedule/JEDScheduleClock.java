package justepicdimensions.schedule;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native saved monotonic ticks; dimension /time changes and unloaded levels cannot alter it. */
@EventBusSubscriber(modid="justepicdimensions")
public final class JEDScheduleClock {
    private static final String KEY="justepicdimensions_schedule_clock";
    private static final Map<MinecraftServer,Counter> COUNTERS=new IdentityHashMap<>();
    private static Counter counter(MinecraftServer server){
        if(!server.isSameThread())throw new IllegalStateException("Schedule clocks require the server thread");
        var known=COUNTERS.get(server);if(known!=null)return known;var storage=server.overworld().getDataStorage();var factory=new SavedData.Factory<>(Counter::new,Counter::read);var value=storage.get(factory,KEY);
        if(value==null&&java.nio.file.Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data/"+KEY+".dat")))throw new IllegalStateException("Cannot read saved JED schedule clock; existing data retained");
        if(value==null){value=new Counter();storage.set(KEY,value);}COUNTERS.put(server,value);return value;
    }
    public static long ticks(MinecraftServer server){return counter(server).ticks;}
    public static void atLeast(MinecraftServer server,long hint){if(hint<0)throw new IllegalArgumentException("Negative saved tick hint");var counter=counter(server);if(hint>counter.ticks){counter.ticks=hint;counter.setDirty();}}
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void tick(ServerTickEvent.Post event){var counter=COUNTERS.get(event.getServer());if(counter!=null&&counter.ticks<Long.MAX_VALUE){counter.ticks++;counter.setDirty();}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){COUNTERS.remove(event.getServer());}
    private static final class Counter extends SavedData {
        long ticks;
        static Counter read(CompoundTag tag,HolderLookup.Provider registries){if(tag.getInt("format_version")!=1||!tag.contains("ticks",Tag.TAG_LONG)||tag.getLong("ticks")<0)throw new IllegalStateException("Invalid saved schedule clock; data retained");var value=new Counter();value.ticks=tag.getLong("ticks");return value;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putInt("format_version",1);tag.putLong("ticks",ticks);return tag;}
    }
    private JEDScheduleClock(){}
}
