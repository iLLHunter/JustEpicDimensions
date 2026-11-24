package justepicdimensions.client;

import justepicdimensions.time.*;
import net.minecraft.client.multiplayer.ClientLevel;

/** Natural progression and NeoForge's explicit-rule payload must retain signed raw clock values. */
public final class JEDClientTimeRuntime {
    public static void advance(ClientLevel level,long proposed) {
        var policy=JEDTimeController.policy(level);
        if(policy==null)level.setDayTime(proposed);
        else level.getLevelData().setDayTime(JEDTimeMath.advance(policy,level.getDayTime(),proposed-level.getDayTime()));
    }
    public static void receivedRaw(ClientLevel level,long time) {
        if(JEDTimeController.policy(level)==null)level.setDayTime(time);
        else level.getLevelData().setDayTime(time);
    }
    private JEDClientTimeRuntime() {}
}
