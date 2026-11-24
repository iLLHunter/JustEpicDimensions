package justepicdimensions.lighting;

import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.JustEpicDimensions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Property-sync boundary for the dimension manager. Inputs are already server-resolved policies. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDLightPolicySync {
    public record Snapshot(long revision,Map<ResourceLocation,JEDClientLightPolicy> policies,Map<ResourceLocation,JEDBrightnessTable> brightnessTables) implements CustomPacketPayload {
        public Snapshot { if(revision<1)throw new IllegalArgumentException("Invalid lighting snapshot revision"); policies=Map.copyOf(policies); brightnessTables=Map.copyOf(brightnessTables); }
        public Snapshot(long revision,Map<ResourceLocation,JEDClientLightPolicy> policies){this(revision,policies,Map.of());}
        static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(JustEpicDimensions.ID,"client_light_policies"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=ByteBufCodecs.COMPOUND_TAG.map(JEDLightPolicySync::decode,JEDLightPolicySync::encode).cast();
        public Type<Snapshot> type(){return TYPE;}
    }
    private static final Map<MinecraftServer,Snapshot> SERVERS=new IdentityHashMap<>();
    private static Consumer<Snapshot> client=s->{};
    public static void clientReceiver(Consumer<Snapshot> receiver){client=receiver;}
    public static void applyClient(Snapshot snapshot){client.accept(snapshot);}
    public static void register(RegisterPayloadHandlersEvent event){
        event.registrar("2").playToClient(Snapshot.TYPE,Snapshot.CODEC,(payload,context)->{
            if(!justepicdimensions.network.JEDPayloads.current(context))return;
            try{client.accept(payload);}catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().error("JED client lighting snapshot rejected",error);justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.sync",justepicdimensions.JEDText.error(error)));}
        });
    }
    public static void publish(MinecraftServer server,Map<ResourceLocation,JEDClientLightPolicy> resolved){
        var previous=SERVERS.get(server);publish(server,resolved,previous==null?Map.of():previous.brightnessTables());
    }
    public static void publish(MinecraftServer server,Map<ResourceLocation,JEDClientLightPolicy> resolved,Map<ResourceLocation,JEDBrightnessTable> brightness){
        if(!server.isSameThread())throw new IllegalStateException("JED policy publication must run on the server thread");
        var old=SERVERS.get(server);var next=new Snapshot(old==null?1:old.revision+1,resolved,brightness);SERVERS.put(server,next);
        if(!justepicdimensions.dimension.JEDDimensionSync.publishing(server))for(var player:server.getPlayerList().getPlayers())PacketDistributor.sendToPlayer(player,next);
    }
    /** Read-only server-thread diagnostic used by loader acceptance tests and policy inspection. */
    public static Optional<Snapshot> current(MinecraftServer server){
        if(!server.isSameThread())throw new IllegalStateException("JED policy inspection must run on the server thread");
        return Optional.ofNullable(SERVERS.get(server));
    }
    // Login and registry publication include the current policy as the first item of their batch.
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){SERVERS.remove(event.getServer());JEDBrightnessTables.stopped(event.getServer());}
    private static CompoundTag encode(Snapshot value){
        var tag=new CompoundTag();tag.putLong("revision",value.revision);var policies=new CompoundTag();
        value.policies.forEach((id,mode)->policies.putString(id.toString(),mode.id()));tag.put("policies",policies);
        var brightness=new CompoundTag();value.brightnessTables.forEach((id,table)->brightness.put(id.toString(),table.toTag()));tag.put("brightness_tables",brightness);return tag;
    }
    private static Snapshot decode(CompoundTag tag){
        if(!tag.contains("revision",net.minecraft.nbt.Tag.TAG_LONG)||!tag.contains("policies",net.minecraft.nbt.Tag.TAG_COMPOUND))throw new IllegalArgumentException("Missing or mistyped lighting snapshot header");
        var policies=new HashMap<ResourceLocation,JEDClientLightPolicy>();var data=tag.getCompound("policies");
        for(String id:data.getAllKeys())policies.put(ResourceLocation.parse(id),JEDClientLightPolicy.parse(data.getString(id)));
        if(!tag.contains("brightness_tables",net.minecraft.nbt.Tag.TAG_COMPOUND))throw new IllegalArgumentException("Missing brightness table snapshot");
        var tables=new HashMap<ResourceLocation,JEDBrightnessTable>();var brightness=tag.getCompound("brightness_tables");
        for(String id:brightness.getAllKeys())tables.put(ResourceLocation.parse(id),JEDBrightnessTable.fromTag(brightness.get(id)));
        return new Snapshot(tag.getLong("revision"),policies,tables);
    }
    private JEDLightPolicySync(){}
}
