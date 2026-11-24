package justepicdimensions.world;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.timers.TimerQueue;

public final class JEDFunctionContext {
    private static final ThreadLocal<ServerLevel> CURRENT=new ThreadLocal<>();
    public static void tick(TimerQueue<MinecraftServer> queue,MinecraftServer server,long time,ServerLevel level){var previous=CURRENT.get();CURRENT.set(level);try{queue.tick(server,time);}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
    public static CommandSourceStack source(CommandSourceStack source){var level=CURRENT.get();return level==null?source:source.withLevel(level);}
    private JEDFunctionContext(){}
}
