package justepicdimensions.travel;

import justepicdimensions.JustEpicDimensions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Client values are informational. All permission checks execute on the server. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDTravelSync {
    public record Settings(boolean tpjAdminOnly,int tpjAdminLevel) implements CustomPacketPayload {
        public static final Type<Settings> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:travel_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Settings> CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,Settings::tpjAdminOnly,ByteBufCodecs.VAR_INT,Settings::tpjAdminLevel,Settings::new);
        public Settings {if(tpjAdminLevel<0||tpjAdminLevel>4)throw new IllegalArgumentException("Invalid JED permission level");}
        public Type<Settings> type(){return TYPE;}
    }
    private static volatile JEDTravelOptions client=JEDTravelOptions.DEFAULT;
    public static JEDTravelOptions clientOptions(){return client;}
    public static void clearClient(){client=JEDTravelOptions.DEFAULT;}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("1").playToClient(Settings.TYPE,Settings.CODEC,(value,context)->{if(justepicdimensions.network.JEDPayloads.current(context))client=new JEDTravelOptions(value.tpjAdminOnly(),value.tpjAdminLevel());});}
    public static void send(ServerPlayer player){var value=JEDTeleportService.options(player.server);PacketDistributor.sendToPlayer(player,new Settings(value.tpjAdminOnly(),value.tpjAdminLevel()));}
    public static void publish(MinecraftServer server){for(var player:server.getPlayerList().getPlayers()){send(player);server.getCommands().sendCommands(player);}}
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player)send(player);}
    private JEDTravelSync(){}
}
