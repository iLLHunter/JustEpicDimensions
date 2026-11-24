package justepicdimensions.provider;

import java.util.*;
import java.util.function.Consumer;
import justepicdimensions.definition.JEDProviderFactoryRegistry;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.*;

/** Native configuration task: missing client code is rejected before any player/world login packets. */
public final class JEDProviderConfiguration {
    public static final ConfigurationTask.Type TASK=new ConfigurationTask.Type(ResourceLocation.parse("justepicdimensions:client_providers"));
    public record Requirements(Map<ResourceLocation,Profile> providers) implements CustomPacketPayload {
        public Requirements{providers=Map.copyOf(providers);}
        public static final Type<Requirements> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:client_providers"));
        public static final StreamCodec<FriendlyByteBuf,Requirements> CODEC=ByteBufCodecs.COMPOUND_TAG.map(JEDProviderConfiguration::decode,JEDProviderConfiguration::encode).cast();
        public Type<Requirements> type(){return TYPE;}
    }
    public record Ready(boolean accepted) implements CustomPacketPayload {
        public static final Type<Ready> TYPE=new Type<>(ResourceLocation.parse("justepicdimensions:client_providers_ready"));
        public static final StreamCodec<FriendlyByteBuf,Ready> CODEC=ByteBufCodecs.BOOL.map(Ready::new,Ready::accepted).cast();
        public Type<Ready> type(){return TYPE;}
    }
    private record Check(MinecraftServer server,net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener listener) implements ICustomConfigurationTask {
        public void run(Consumer<CustomPacketPayload> sender){
            Runnable prepare=()->{
                if(!listener.getConnection().isConnected())return;
                var required=JEDProviderController.clientRequirements(server);if(required.isEmpty()){listener.finishCurrentTask(TASK);return;}
                if(!listener.hasChannel(Requirements.TYPE)||!listener.hasChannel(Ready.TYPE)){listener.disconnect(justepicdimensions.JEDText.tr("connection.providers_missing"));return;}
                sender.accept(new Requirements(required));
            };
            if(server.isSameThread())prepare.run();else server.execute(prepare);
        }
        public ConfigurationTask.Type type(){return TASK;}
    }
    public static void register(RegisterPayloadHandlersEvent event){var registrar=event.registrar("1");
        registrar.configurationToClient(Requirements.TYPE,Requirements.CODEC,(requirements,context)->{
            if(!justepicdimensions.network.JEDPayloads.current(context))return;
            try{JEDProviderFactoryRegistry.requireClients(requirements.providers());context.reply(new Ready(true));}
            catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().warn("JED provider configuration rejected: {}",error.getMessage());justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.error(error));}
        });
        registrar.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK).configurationToServer(Ready.TYPE,Ready.CODEC,(ready,context)->justepicdimensions.network.JEDPayloadWork.enqueue(context,1,()->{
            try{if(ready.accepted())context.finishCurrentTask(TASK);else justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.providers_rejected"));}
            catch(RuntimeException error){com.mojang.logging.LogUtils.getLogger().warn("JED provider configuration acknowledgment rejected",error);justepicdimensions.network.JEDPayloads.disconnect(context, justepicdimensions.JEDText.tr("connection.providers_rejected"));}
        }));
    }
    public static void tasks(RegisterConfigurationTasksEvent event){
        var listener=event.getListener();event.register(new Check((MinecraftServer)listener.getMainThreadEventLoop(),listener));
    }
    private static CompoundTag encode(Requirements requirements){var tag=new CompoundTag();requirements.providers().forEach((id,profile)->tag.putString(id.toString(),profile.name()));return tag;}
    private static Requirements decode(CompoundTag tag){var result=new TreeMap<ResourceLocation,Profile>();for(var id:tag.getAllKeys())result.put(ResourceLocation.parse(id),Profile.valueOf(tag.getString(id)));return new Requirements(result);}
    private JEDProviderConfiguration(){}
}
