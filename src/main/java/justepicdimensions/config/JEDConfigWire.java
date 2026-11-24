package justepicdimensions.config;

import java.util.function.Consumer;
import justepicdimensions.builder.JEDBuilderWire;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Separate config payloads using the existing bounded, ordered message framing. MAIN thread by default. */
public final class JEDConfigWire {
    private static final StreamCodec<RegistryFriendlyByteBuf,JEDBuilderWire.Frame> FRAMES = new StreamCodec<>() {
        public JEDBuilderWire.Frame decode(RegistryFriendlyByteBuf b) { return new JEDBuilderWire.Frame(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readUtf(JEDBuilderWire.CHUNK)); }
        public void encode(RegistryFriendlyByteBuf b,JEDBuilderWire.Frame f) { b.writeUUID(f.session());b.writeVarInt(f.request());b.writeVarInt(f.index());b.writeVarInt(f.count());b.writeUtf(f.text(),JEDBuilderWire.CHUNK); }
    };
    public record Request(JEDBuilderWire.Frame frame) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:config_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=FRAMES.map(Request::new,Request::frame);
        public Type<Request> type(){return TYPE;}
    }
    public record Response(JEDBuilderWire.Frame frame) implements CustomPacketPayload {
        public static final Type<Response> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:config_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Response> CODEC=FRAMES.map(Response::new,Response::frame);
        public Type<Response> type(){return TYPE;}
    }
    private static Consumer<JEDBuilderWire.Frame> receiver=f->{};
    public static void receiver(Consumer<JEDBuilderWire.Frame> consumer){receiver=consumer;}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("1").executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK)
        .playToServer(Request.TYPE,Request.CODEC,(p,c)->justepicdimensions.network.JEDPayloadWork.enqueue(c,p.frame().text().length()*2+64,()->JEDConfigGateway.receive(p.frame(),c)))
        .executesOn(net.neoforged.neoforge.network.registration.HandlerThread.MAIN).playToClient(Response.TYPE,Response.CODEC,(p,c)->{if(justepicdimensions.network.JEDPayloads.current(c))receiver.accept(p.frame());});}
    private JEDConfigWire(){}
}
