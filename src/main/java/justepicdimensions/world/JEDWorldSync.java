package justepicdimensions.world;

import java.util.function.BiConsumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registry-free entry metadata, ordered before the native login/respawn construction packet. */
public final class JEDWorldSync {
    private static final java.util.Map<net.minecraft.server.MinecraftServer,Update> UPDATES=new java.util.IdentityHashMap<>();
    /** Compare the complete before/after state on one server-thread commit, without intermediate sends. */
    public static final class Update implements AutoCloseable {
        private final net.minecraft.server.MinecraftServer server;
        private final java.util.Map<ServerLevel,CompoundTag> before=new java.util.IdentityHashMap<>();
        private boolean committed,closed;
        private Update(net.minecraft.server.MinecraftServer server){this.server=server;for(var level:server.getAllLevels())if(!level.players().isEmpty())before.put(level,snapshot(level).data());}
        public void commit(){committed=true;}
        public void close(){
            if(closed)return;closed=true;UPDATES.remove(server,this);
            if(committed)for(var level:server.getAllLevels())if(!level.players().isEmpty()){
                var value=snapshot(level);if(!value.data().equals(before.get(level)))send(level,value);
            }
        }
    }
    public static Update update(net.minecraft.server.MinecraftServer server){
        if(!server.isSameThread()||UPDATES.containsKey(server))throw new IllegalStateException("World metadata updates require a non-nested server-thread commit");
        var update=new Update(server);UPDATES.put(server,update);return update;
    }
    public record Snapshot(ResourceLocation dimension,CompoundTag data) implements CustomPacketPayload {
        public Snapshot {data=data.copy();}
        public static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:world_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=ByteBufCodecs.COMPOUND_TAG.map(tag->new Snapshot(ResourceLocation.parse(tag.getString("dimension")),tag.getCompound("state")),snapshot->{var tag=new CompoundTag();tag.putString("dimension",snapshot.dimension.toString());tag.put("state",snapshot.data);return tag;}).cast();
        public Type<Snapshot> type(){return TYPE;}
    }
    private static BiConsumer<Snapshot,net.neoforged.neoforge.network.handling.IPayloadContext> CLIENT=(snapshot,context)->{};
    public static void receiver(BiConsumer<Snapshot,net.neoforged.neoforge.network.handling.IPayloadContext> receiver){CLIENT=receiver;}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("5").playToClient(Snapshot.TYPE,Snapshot.CODEC,(snapshot,context)->{if(!justepicdimensions.network.JEDPayloads.current(context))return;try{CLIENT.accept(snapshot,context);}catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().error("JED client world settings rejected for {}",snapshot.dimension(),error);justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.world",snapshot.dimension(),justepicdimensions.JEDText.error(error)));}});}
    public static Snapshot snapshot(ServerLevel level){var state=level.getLevelData();var data=new CompoundTag();
        data.putLong("Time",state.getGameTime());data.putLong("DayTime",state.getDayTime());data.putFloat("neoDayTimeFraction",level.getDayTimeFraction());data.putFloat("neoDayTimePerTick",level.getDayTimePerTick());
        data.putByte("Difficulty",(byte)state.getDifficulty().getId());data.putBoolean("DifficultyLocked",state.isDifficultyLocked());data.putBoolean("hardcore",state.isHardcore());data.putBoolean("raining",state.isRaining());
        var spawn=level.getSharedSpawnPos();data.putInt("SpawnX",spawn.getX());data.putInt("SpawnY",spawn.getY());data.putInt("SpawnZ",spawn.getZ());data.putFloat("SpawnAngle",level.getSharedSpawnAngle());data.put("GameRules",state.getGameRules().createTag());
        data.putFloat("rain_level",level.getRainLevel(1));data.putFloat("thunder_level",level.getThunderLevel(1));
        var time=justepicdimensions.time.JEDTimeController.policy(level);if(time!=null)data.putString("jed_time_policy",time.json().toString());
        var render=justepicdimensions.render.JEDRenderController.policy(level);if(render!=null)data.putString("jed_render_policy",render.json().toString());
        data.putBoolean("jed_local_border",JEDWorldControl.options(level.getServer()).borders());
        justepicdimensions.provider.JEDProviderController.writeClient(level,data);
        return new Snapshot(level.dimension().location(),data);
    }
    public static void beforeEntry(ServerPlayer player,ServerLevel destination){if(player.connection!=null)PacketDistributor.sendToPlayer(player,snapshot(destination));}
    public static void entry(ServerPlayer player,ServerLevel destination){beforeEntry(player,destination);}
    public static void changed(ServerLevel level){if(!UPDATES.containsKey(level.getServer())&&!level.players().isEmpty())send(level,snapshot(level));}
    private static void send(ServerLevel level,Snapshot value){for(var player:level.players())PacketDistributor.sendToPlayer(player,value);}
    private JEDWorldSync(){}
}
