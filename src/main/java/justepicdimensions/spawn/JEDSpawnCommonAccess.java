package justepicdimensions.spawn;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/** Transfer native keep-alive state before publishing a replacement listener. */
public interface JEDSpawnCommonAccess {
    boolean jed$handoffTo(ServerCommonPacketListenerImpl target);
}
