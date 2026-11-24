package justepicdimensions.spawn;

import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;

/** Same-protocol listener handoff; the native codec is already installed while waiting. */
public interface JEDSpawnConnectionAccess {
    void jed$replacePlayListener(ProtocolInfo<?> protocol,PacketListener listener);
}
