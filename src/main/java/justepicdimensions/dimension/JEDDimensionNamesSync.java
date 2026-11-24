package justepicdimensions.dimension;

import java.util.*;
import com.google.gson.JsonObject;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.builder.JEDBuilderWire;
import justepicdimensions.definition.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Cosmetic, server-owned metadata over bounded ordinary play payloads. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDDimensionNamesSync {
    public record Snapshot(JEDBuilderWire.Frame frame) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:dimension_names"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Snapshot> CODEC=JEDBuilderWire.Response.CODEC.map(v->new Snapshot(v.frame()),v->new JEDBuilderWire.Response(v.frame()));
        public Type<Snapshot> type(){return TYPE;}
    }
    private static volatile Map<ResourceLocation,String> client=Map.of();
    private static final JEDBuilderWire.Assembly IN=new JEDBuilderWire.Assembly();
    private static UUID token;private static long revision=-1;
    private static final Map<MinecraftServer,Map<ResourceLocation,String>> SENT=new IdentityHashMap<>();
    public static Map<ResourceLocation,String> clientNames(){return client;}
    public static void clearClient(){client=Map.of();revision=-1;token=null;IN.clear();}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("1").playToClient(Snapshot.TYPE,Snapshot.CODEC,(value,context)->{
        if(!justepicdimensions.network.JEDPayloads.current(context))return;
        try{receive(value.frame());}catch(RuntimeException error){clearClient();com.mojang.logging.LogUtils.getLogger().warn("JED dimension display names were rejected",error);}
    });}
    public static Map<ResourceLocation,String> names(MinecraftServer server){
        var names=new TreeMap<ResourceLocation,String>();var registered=server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        JEDDefinitionManager.state(server).definitions().forEach((id,definition)->{String name=definition.displayName();if(name!=null&&registered.containsKey(id))names.put(id,name);});return Map.copyOf(names);
    }
    public static List<Snapshot> snapshot(MinecraftServer server){
        var json=new JsonObject();json.addProperty("revision",JEDDefinitionManager.state(server).revision());var values=new JsonObject();names(server).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->values.addProperty(e.getKey().toString(),e.getValue()));json.add("names",values);
        return JEDBuilderWire.split(UUID.randomUUID(),0,json.toString()).stream().map(Snapshot::new).toList();
    }
    public static void publish(MinecraftServer server){var names=names(server);if(names.equals(SENT.put(server,names))||server.getPlayerList().getPlayers().isEmpty())return;var frames=snapshot(server);for(var player:server.getPlayerList().getPlayers())send(player,frames);}
    private static void send(ServerPlayer player,List<Snapshot> frames){for(var frame:frames)PacketDistributor.sendToPlayer(player,frame);}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player)send(player,snapshot(player.server));}
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){SENT.remove(event.getServer());}
    public static void receive(JEDBuilderWire.Frame frame){
        if(frame.index()==0){IN.clear();token=frame.session();}
        if(!Objects.equals(token,frame.session()))throw new JEDDefinitionException("Unexpected dimension-name transfer");
        String text=IN.accept(frame,System.nanoTime());if(text==null)return;
        var json=JEDBuilderWire.json(text);JEDJson.keys(json,Set.of("revision","names"),"dimension names");
        long next=json.get("revision").getAsBigDecimal().longValueExact();if(next<0)throw new JEDDefinitionException("Invalid dimension-name revision");
        var names=new TreeMap<ResourceLocation,String>();var values=JEDJson.object(json.get("names"),"dimension names");
        values.entrySet().forEach(e->{var id=ResourceLocation.parse(e.getKey());String name=JEDJson.string(e.getValue(),e.getKey());if(name.isBlank()||name.length()>256)throw new JEDDefinitionException("Invalid dimension display name for "+id);names.put(id,name);});
        if(next<revision)return;client=Map.copyOf(names);revision=next;
    }
    private JEDDimensionNamesSync(){}
}
