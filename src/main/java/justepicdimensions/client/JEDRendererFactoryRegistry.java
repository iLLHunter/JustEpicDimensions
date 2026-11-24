package justepicdimensions.client;

import com.mojang.serialization.*;
import java.util.*;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import justepicdimensions.render.JEDRenderDefinition.Selection;

/** Client addon API. Factories return a fresh per-dimension renderer; AutoCloseable instances are retired. */
public final class JEDRendererFactoryRegistry {
    public enum Channel { SKY, CLOUDS, WEATHER }
    @FunctionalInterface public interface Factory<T> { DimensionSpecialEffects create(ResourceLocation dimension,T settings); }
    private record Entry<T>(Set<Channel> channels,Codec<T> codec,Factory<T> factory){DimensionSpecialEffects create(ResourceLocation dimension,Selection selection){var connection=net.minecraft.client.Minecraft.getInstance().getConnection();com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops=connection==null?JsonOps.INSTANCE:net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE,connection.registryAccess());return Objects.requireNonNull(factory.create(dimension,codec.parse(ops,selection.settings()).getOrThrow()),"Renderer factory returned null");}}
    public record Resolved(boolean selected,boolean hidden,DimensionSpecialEffects renderer) {}
    private static final Map<ResourceLocation,Entry<?>> FACTORIES=new HashMap<>();
    public static synchronized List<ResourceLocation> ids(Channel channel){var ids=new TreeSet<ResourceLocation>();ids.add(ResourceLocation.parse("justepicdimensions:native"));ids.add(ResourceLocation.parse("justepicdimensions:none"));FACTORIES.forEach((id,entry)->{if(entry.channels().contains(channel))ids.add(id);});return List.copyOf(ids);}
    public static synchronized <T> void register(ResourceLocation id,Set<Channel> channels,Codec<T> codec,Factory<T> factory){
        if(id.getNamespace().equals("justepicdimensions")&&(id.getPath().equals("native")||id.getPath().equals("none")))throw new IllegalArgumentException("Reserved renderer ID "+id);
        if(channels.isEmpty()||FACTORIES.putIfAbsent(id,new Entry<>(Set.copyOf(channels),Objects.requireNonNull(codec),Objects.requireNonNull(factory)))!=null)throw new IllegalArgumentException("Empty channels or duplicate renderer ID "+id);
    }
    public static synchronized Resolved resolve(ResourceLocation dimension,Channel channel,Selection selection){
        if(selection==null)return new Resolved(false,false,null);
        if(selection.id().getNamespace().equals("justepicdimensions")&&Set.of("native","none").contains(selection.id().getPath())){
            if(!selection.settings().isEmpty()){JEDClientRenderRuntime.warn(dimension,"renderer-"+channel,"Renderer "+selection.id()+" does not accept settings; using native rendering",justepicdimensions.JEDText.tr("render.settings",selection.id()));return new Resolved(false,false,null);}
            return new Resolved(true,selection.id().getPath().equals("none"),null);
        }
        var entry=FACTORIES.get(selection.id());if(entry==null||!entry.channels().contains(channel)){JEDClientRenderRuntime.warn(dimension,"renderer-"+channel,"Renderer "+selection.id()+" is unavailable for "+channel.name().toLowerCase(Locale.ROOT)+"; using native rendering",justepicdimensions.JEDText.tr("render.unavailable",selection.id(),channel.name().toLowerCase(Locale.ROOT)));return new Resolved(false,false,null);}
        try{return new Resolved(true,false,entry.create(dimension,selection));}catch(RuntimeException error){JEDClientRenderRuntime.warn(dimension,"renderer-"+channel,"Renderer "+selection.id()+" could not be configured: "+error.getMessage()+"; using native rendering",justepicdimensions.JEDText.tr("render.failed",selection.id(),justepicdimensions.JEDText.error(error)));return new Resolved(false,false,null);}
    }
    private JEDRendererFactoryRegistry(){}
}
