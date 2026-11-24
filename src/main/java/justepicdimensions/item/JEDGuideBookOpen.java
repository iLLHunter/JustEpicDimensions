package justepicdimensions.item;

import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The native open-book packet only accepts the exact vanilla item in some optional readers. */
public record JEDGuideBookOpen(InteractionHand hand) implements CustomPacketPayload {
    public static final Type<JEDGuideBookOpen> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:open_guide_book"));
    public static final StreamCodec<RegistryFriendlyByteBuf,JEDGuideBookOpen> CODEC=new StreamCodec<>(){
        public void encode(RegistryFriendlyByteBuf buffer,JEDGuideBookOpen value){buffer.writeEnum(value.hand());}
        public JEDGuideBookOpen decode(RegistryFriendlyByteBuf buffer){return new JEDGuideBookOpen(buffer.readEnum(InteractionHand.class));}
    };
    private static Consumer<InteractionHand> receiver=hand->{};
    public static void receiver(Consumer<InteractionHand> handler){receiver=handler;}
    public Type<JEDGuideBookOpen> type(){return TYPE;}
    public static void register(RegisterPayloadHandlersEvent event){event.registrar("1").executesOn(net.neoforged.neoforge.network.registration.HandlerThread.MAIN).playToClient(TYPE,CODEC,(payload,context)->{if(justepicdimensions.network.JEDPayloads.current(context))receiver.accept(payload.hand());});}
}
