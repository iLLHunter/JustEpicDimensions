package justepicdimensions.world;

import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameRules;

/** Invoke the original callback, preserving addon behavior and constraining native packet recipients. */
public final class JEDGameruleCallbacks {
    private static final Map<GameRules.Value<?>,ServerLevel> OWNERS=new IdentityHashMap<>();
    private static final ThreadLocal<GameRules.Value<?>> ACTIVE=new ThreadLocal<>();
    public static void bind(ServerLevel level){if(level.getLevelData() instanceof JEDLevelData)GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor(){public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key,GameRules.Type<T> type){OWNERS.put(level.getGameRules().getRule(key),level);}});}
    public static void unbind(ServerLevel level){OWNERS.values().removeIf(owner->owner==level);}
    public static void stopped(MinecraftServer server){OWNERS.values().removeIf(level->level.getServer()==server);}
    @SuppressWarnings({"rawtypes","unchecked"}) public static void changed(BiConsumer callback,Object server,Object value){
        var previous=ACTIVE.get();ACTIVE.set((GameRules.Value<?>)value);
        try{callback.accept(server,value);}finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);var owner=OWNERS.get(value);if(owner!=null)JEDWorldSync.changed(owner);else if(server instanceof MinecraftServer nativeServer)for(var level:nativeServer.getAllLevels())if(!(level.getLevelData() instanceof JEDLevelData))JEDWorldSync.changed(level);}
    }
    public static List<ServerPlayer> players(PlayerList players){
        var value=ACTIVE.get();if(value==null)return players.getPlayers();var owner=OWNERS.get(value);if(owner!=null)return List.copyOf(owner.players());
        // Native primary rules remain shared by vanilla dimensions, but must not overwrite an independent client's flags.
        return players.getPlayers().stream().filter(player->!(player.level().getLevelData() instanceof JEDLevelData)).toList();
    }
    public static ServerLevel level(MinecraftServer server){var owner=OWNERS.get(ACTIVE.get());return owner==null?server.overworld():owner;}
    public static void spawnPacket(PlayerList players,net.minecraft.network.protocol.Packet<?> packet,ServerLevel level){for(var player:level.players())player.connection.send(packet);}
    private JEDGameruleCallbacks(){}
}
