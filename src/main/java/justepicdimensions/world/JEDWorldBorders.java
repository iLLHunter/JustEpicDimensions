package justepicdimensions.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.border.*;
import net.minecraft.network.protocol.game.*;

public final class JEDWorldBorders {
    public static WorldBorder entry(net.minecraft.server.MinecraftServer server,ServerLevel level){return JEDWorldControl.options(server).borders()?level.getWorldBorder():server.overworld().getWorldBorder();}
    public static ServerLevel level(net.minecraft.server.MinecraftServer server,ServerLevel level){return JEDWorldControl.options(server).borders()?level:server.overworld();}
    public static boolean separate(PlayerList players){return JEDWorldControl.options(players.getServer()).borders();}
    public static void listen(ServerLevel level){level.getWorldBorder().addListener(new BorderChangeListener(){
        private void send(net.minecraft.network.protocol.Packet<?> packet){for(var player:level.players())player.connection.send(packet);}
        public void onBorderSizeSet(WorldBorder border,double size){send(new ClientboundSetBorderSizePacket(border));}
        public void onBorderSizeLerping(WorldBorder border,double from,double to,long duration){send(new ClientboundSetBorderLerpSizePacket(border));}
        public void onBorderCenterSet(WorldBorder border,double x,double z){send(new ClientboundSetBorderCenterPacket(border));}
        public void onBorderSetWarningTime(WorldBorder border,int value){send(new ClientboundSetBorderWarningDelayPacket(border));}
        public void onBorderSetWarningBlocks(WorldBorder border,int value){send(new ClientboundSetBorderWarningDistancePacket(border));}
        public void onBorderSetDamagePerBlock(WorldBorder border,double value){}
        public void onBorderSetDamageSafeZOne(WorldBorder border,double value){}
    });}
    private JEDWorldBorders(){}
}
