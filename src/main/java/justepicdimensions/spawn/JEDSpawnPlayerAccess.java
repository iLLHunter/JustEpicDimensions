package justepicdimensions.spawn;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;

/** Injected forwarding method retains native anchor and modded respawn-block resolution. */
public interface JEDSpawnPlayerAccess {
    Optional<ServerPlayer.RespawnPosAngle> jed$resolveSpawn(ServerLevel level,BlockPos position,float angle,boolean forced,boolean keepEverything);
}
