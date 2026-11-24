package justepicdimensions.travel;

import justepicdimensions.JustEpicDimensions;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Check before native entity damage, without scanning chunks or loading terrain during a check. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDVerticalTeleportController {
    @SubscribeEvent(priority=EventPriority.HIGH) public static void level(LevelTickEvent.Pre event){
        if(!(event.getLevel() instanceof ServerLevel level))return;
        var policy=JEDTeleportService.policy(level);if((policy.sky()==null&&policy.below()==null)||level.getServer().getTickCount()%policy.interval()!=0)return;
        for(var entity:level.getAllEntities())check(entity,level);
    }
    @SubscribeEvent(priority=EventPriority.HIGH) public static void tick(EntityTickEvent.Pre event){
        var entity=event.getEntity();if(!(entity.level() instanceof ServerLevel level))return;check(entity,level);if(JEDTeleportService.pending(entity))JEDTeleportService.hold(event);
    }
    private static void check(net.minecraft.world.entity.Entity entity,ServerLevel level){
        if(entity.isRemoved()||!entity.isAlive()||JEDTeleportService.pending(entity)||JEDTeleportService.cooling(entity))return;
        var policy=JEDTeleportService.policy(level);if(level.getServer().getTickCount()%policy.interval()!=0)return;
        var rule=policy.below()!=null&&entity.getY()<policy.below().triggerY()?policy.below():policy.sky()!=null&&entity.getY()>policy.sky().triggerY()?policy.sky():null;
        if(rule!=null)JEDTeleportService.automatic(entity,rule);
    }
    private JEDVerticalTeleportController(){}
}
