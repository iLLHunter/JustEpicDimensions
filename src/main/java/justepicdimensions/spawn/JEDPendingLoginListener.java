package justepicdimensions.spawn;

import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;

/** Play protocol has begun, but no player/world entry has been published yet. */
public final class JEDPendingLoginListener extends ServerGamePacketListenerImpl {
    private volatile int keepAliveResponses;
    private volatile boolean closing;
    public JEDPendingLoginListener(MinecraftServer server,Connection connection,ServerPlayer player,CommonListenerCookie cookie){
        super(server,connection,player,cookie);
        connection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(),getConnectionType())),this);
    }
    @Override public synchronized void tick(){if(!closing)keepConnectionAlive();}
    @Override public boolean isAcceptingMessages(){return !closing&&super.isAcceptingMessages();}
    private static boolean connectionPayload(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload){return payload instanceof net.neoforged.neoforge.network.payload.MinecraftRegisterPayload||payload instanceof net.neoforged.neoforge.network.payload.MinecraftUnregisterPayload||payload instanceof net.neoforged.neoforge.network.payload.CommonVersionPayload||payload instanceof net.neoforged.neoforge.network.payload.CommonRegisterPayload;}
    @Override public void send(Packet<?> packet,PacketSendListener listener){if(packet.getClass().getPackageName().equals("net.minecraft.network.protocol.common")&&(!(packet instanceof ClientboundCustomPayloadPacket payload)||connectionPayload(payload.payload())))super.send(packet,listener);}
    @Override public boolean shouldHandleMessage(Packet<?> packet){return !closing&&connection.isConnected()&&packet.getClass().getPackageName().equals("net.minecraft.network.protocol.common")&&(!(packet instanceof ServerboundCustomPayloadPacket payload)||connectionPayload(payload.payload()));}
    @Override public void handleKeepAlive(ServerboundKeepAlivePacket packet){
        // Retain native Netty handling. The same short lock protects tick/handoff state;
        // pending disconnect queues cleanup instead of waiting for the server under it.
        ServerGamePacketListenerImpl replacement=null;
        synchronized(this){
            if(closing||!connection.isConnected())return;
            var current=connection.getPacketListener();
            if(current==this){super.handleKeepAlive(packet);if(!closing&&connection.isConnected())keepAliveResponses++;return;}
            if(current instanceof ServerGamePacketListenerImpl game)replacement=game;
        }
        if(replacement!=null)replacement.handleKeepAlive(packet);
    }
    public boolean closing(){return closing;}
    @Override public void disconnect(DisconnectionDetails details){
        // Native disconnect uses executeBlocking. Never invoke it from Netty while the
        // server can be completing a protocol boundary on that same connection.
        synchronized(this){if(closing)return;closing=true;}
        if(server.isSameThread())super.disconnect(details);else server.execute(()->super.disconnect(details));
    }
    public int keepAliveResponses(){return keepAliveResponses;}
    @Override public void onDisconnect(DisconnectionDetails details){
        closing=true;
        // The ordinary game listener saves/removes a joined player. This player has never
        // been placed, so preserve the fresh-player state and the configuration listener's
        // integrated-owner shutdown behavior instead.
        server.execute(()->{JEDSpawnController.abandonLogin(player);if(server.isSingleplayerOwner(player.getGameProfile()))server.halt(false);});
    }
}
