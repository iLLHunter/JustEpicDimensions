package justepicdimensions.definition;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;

/** Routine information only. Conflict reports and gameplay failure notices never pass through this gate. */
public final class JEDInfoLog {
    public static void info(MinecraftServer server,String message,Object... arguments){info(JEDDefinitionManager.state(server).prepared().controls(),message,arguments);}
    public static void info(JEDConfigControls controls,String message,Object... arguments){if(controls.information())LogUtils.getLogger().info(message,arguments);}
    public static void startup(String message,Object... arguments){info(JEDConfigControls.read(JEDConfigRepository.select(null)),message,arguments);}
    private JEDInfoLog(){}
}
