package justepicdimensions.access;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;

/** Native respawn continuation, after destination callbacks and before constructing the replacement player. */
public interface JEDAccessRespawnAccess {
    ServerPlayer jed$resumeRespawn(ServerPlayer player,boolean keep,Entity.RemovalReason reason,DimensionTransition transition,net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent event);
}
