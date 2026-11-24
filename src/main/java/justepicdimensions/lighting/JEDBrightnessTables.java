package justepicdimensions.lighting;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;

/** Server and client ownership are separate, including in an integrated game. No shared type mutation. */
public final class JEDBrightnessTables {
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDBrightnessTable>> servers = Map.of();
    public static final class ClientState {
        private volatile JEDLightPolicySync.Snapshot snapshot;
        public boolean apply(JEDLightPolicySync.Snapshot next) {
            var previous=snapshot;
            if (previous!=null && next.revision()<=previous.revision()) return false;
            snapshot=next; return true;
        }
        public JEDBrightnessTable table(ResourceLocation id) { var state=snapshot; return state==null?null:state.brightnessTables().get(id); }
        public void clear() { snapshot=null; }
    }
    public static final ClientState CLIENT = new ClientState();
    public static synchronized void publish(MinecraftServer server,Map<ResourceLocation,JEDBrightnessTable> tables) {
        var next=new IdentityHashMap<>(servers); next.put(server,Map.copyOf(tables)); servers=Collections.unmodifiableMap(next);
    }
    public static synchronized void stopped(MinecraftServer server) {
        var next=new IdentityHashMap<>(servers); next.remove(server); servers=Collections.unmodifiableMap(next);
    }
    public static JEDBrightnessTable table(LevelReader reader) {
        Level level=reader instanceof Level l?l:reader instanceof ServerLevelAccessor access?access.getLevel():null;
        if (level instanceof ServerLevel serverLevel) return servers.getOrDefault(serverLevel.getServer(),Map.of()).get(level.dimension().location());
        return level!=null && level.isClientSide?CLIENT.table(level.dimension().location()):null;
    }
    /** Receives native result and its already-read raw index; never queries or changes light data. */
    public static float common(float nativeValue,LevelReader reader,int index) {
        var table=table(reader); return table==null?nativeValue:table.value(index);
    }
    private JEDBrightnessTables() {}
}
