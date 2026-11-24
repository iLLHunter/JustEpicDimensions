package justepicdimensions.dimension;

import io.netty.buffer.*;
import io.netty.channel.ChannelPipeline;
import java.util.function.ToLongFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.GameProtocols;

/** Account native encoded size before splitting, retaining the original packet for addon handlers.
 * This is a serialized-data budget, not a measurement of arbitrary addon object graphs. */
final class JEDPacketSizer implements ToLongFunction<Object> {
    private final RegistryAccess registries;
    private final ChannelPipeline pipeline;
    private ProtocolInfo<?> protocol;
    JEDPacketSizer(RegistryAccess registries){this(registries,null);}
    JEDPacketSizer(RegistryAccess registries,ChannelPipeline pipeline){this.registries=registries;this.pipeline=pipeline;}
    @SuppressWarnings({"rawtypes","unchecked"}) public long applyAsLong(Object message){
        if(message instanceof BundlePacket<?> bundle){long total=2;for(var packet:bundle.subPackets()){total+=applyAsLong(packet);if(total>JEDPacketQueue.MAX_BYTES)return total;}return total;}
        if(!(message instanceof Packet<?>))return simple(message);
        var encoder=pipeline==null?null:pipeline.get(PacketEncoder.class);
        ProtocolInfo<?> live=encoder==null?null:encoder.getProtocolInfo();
        if(live==null){if(protocol==null)protocol=GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE));live=protocol;}
        var buffer=ByteBufAllocator.DEFAULT.heapBuffer(256,(int)JEDPacketQueue.MAX_BYTES+1);
        try{((net.minecraft.network.codec.StreamCodec)live.codec()).encode(buffer,message);return 64L+buffer.readableBytes();}
        finally{buffer.release();}
    }
    static long simple(Object message){
        if(message instanceof ByteBuf buffer)return 64L+buffer.readableBytes();
        if(message instanceof ByteBufHolder holder)return 64L+holder.content().readableBytes();
        if(message instanceof CharSequence text)return 64L+text.length()*2L;
        return 64; // Synthetic test pipelines have no native protocol; real connections install a codec sizer.
    }
}
