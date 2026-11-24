package justepicdimensions.definition;

import com.google.gson.*;
import com.mojang.serialization.*;
import java.util.*;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import net.minecraft.resources.ResourceLocation;

/** Registered codecs and executable provider factories. Factories run on the owning game thread. */
public final class JEDProviderFactoryRegistry {
    @FunctionalInterface public interface Factory<T> { justepicdimensions.provider.JEDWorldProvider create(ResourceLocation dimension,T settings); }
    public record Type<T>(ResourceLocation id, Profile profile, Codec<T> settingsCodec,Factory<T> factory,boolean clientBehavior) {}
    public static final class Selection {
        private final Type<?> type;private final Object settings;private final String settingsJson;private final ResourceLocation dimension;
        private justepicdimensions.provider.JEDWorldProvider pending;
        private Selection(Type<?> type,Object settings,String settingsJson,ResourceLocation dimension,justepicdimensions.provider.JEDWorldProvider prepared){this.type=type;this.settings=settings;this.settingsJson=settingsJson;this.dimension=dimension;this.pending=prepared;}
        public Type<?> type(){return type;}public Object settings(){return settings;}public String settingsJson(){return settingsJson;}
        /** Inspection during validation. Active consumers use JEDProviderController.policy(level). */
        public justepicdimensions.provider.JEDWorldProvider prepared(){return pending==null?fresh(dimension):pending;}
        /** Transfer ownership so the saved definition cannot retain an unloaded provider/level. */
        public justepicdimensions.provider.JEDWorldProvider takePrepared(){var value=prepared();pending=null;return value;}
        public justepicdimensions.provider.JEDWorldProvider fresh(ResourceLocation dimension){return create(type,dimension,settings);}
    }
    private static final Map<ResourceLocation, Type<?>> TYPES = new LinkedHashMap<>();
    public static synchronized Set<ResourceLocation> ids(){return Set.copyOf(TYPES.keySet());}
    static {
        register(ResourceLocation.parse("justepicdimensions:surface"), Profile.SURFACE, justepicdimensions.provider.JEDProviderSettings.CODEC);
        register(ResourceLocation.parse("justepicdimensions:base"), Profile.BASE, justepicdimensions.provider.JEDProviderSettings.CODEC);
        register(ResourceLocation.parse("justepicdimensions:nether"), Profile.NETHER, justepicdimensions.provider.JEDProviderSettings.CODEC);
        register(ResourceLocation.parse("justepicdimensions:end"), Profile.END, justepicdimensions.provider.JEDProviderSettings.CODEC);
    }
    public static Type<justepicdimensions.provider.JEDProviderSettings> register(ResourceLocation id,Profile profile,Codec<justepicdimensions.provider.JEDProviderSettings> settings){
        return register(id,profile,settings,(dimension,value)->new justepicdimensions.provider.JEDWorldProvider(profile,value));
    }
    public static <T> Type<T> register(ResourceLocation id,Profile profile,Codec<T> settings,Factory<T> factory){return register(id,profile,settings,factory,false);}
    /** The addon must register this ID on both sides when its provider implements custom client callbacks. */
    public static <T> Type<T> registerWithClient(ResourceLocation id,Profile profile,Codec<T> settings,Factory<T> factory){return register(id,profile,settings,factory,true);}
    private static synchronized <T> Type<T> register(ResourceLocation id,Profile profile,Codec<T> settings,Factory<T> factory,boolean clientBehavior) {
        var type = new Type<>(Objects.requireNonNull(id),Objects.requireNonNull(profile),Objects.requireNonNull(settings),Objects.requireNonNull(factory),clientBehavior);
        if (TYPES.putIfAbsent(id, type) != null) throw new IllegalStateException("Duplicate JED provider type " + id);
        return type;
    }
    public static synchronized Selection decode(ResourceLocation dimension,JsonObject provider,String source,DynamicOps<JsonElement> ops) {
        JEDJson.keys(provider, Set.of("type", "settings"), source);
        ResourceLocation id = JEDDimensionDefinition.id(JEDJson.string(provider.get("type"), source + ".type"), source);
        var type = TYPES.get(id);
        if (type == null) throw new JEDDefinitionException(source + ": unknown registered JED provider " + id);
        var value = provider.has("settings") ? provider.get("settings") : new JsonObject();
        return decode(type,dimension,value,source,ops);
    }
    private static <T> Selection decode(Type<T> type,ResourceLocation dimension,JsonElement value,String source,DynamicOps<JsonElement> ops){
        var decoded = type.settingsCodec().parse(ops, value);
        if (decoded.error().isPresent()) throw new JEDDefinitionException(source + ": " + decoded.error().get().message());
        var settings=decoded.result().orElseThrow();
        return new Selection(type,settings,JEDJson.canonical(value).toString(),dimension,create(type,dimension,settings));
    }
    @SuppressWarnings("unchecked") private static <T> justepicdimensions.provider.JEDWorldProvider create(Type<T> type,ResourceLocation dimension,Object value){
        try{
            var provider=Objects.requireNonNull(type.factory().create(dimension,(T)value),"factory returned null");
            if(provider.profile()!=type.profile())throw new IllegalArgumentException("factory profile does not match registered profile");
            Objects.requireNonNull(provider.settings(),"provider settings");Objects.requireNonNull(provider.clientSettings(),"client settings");return provider;
        }catch(RuntimeException error){throw new JEDDefinitionException(dimension+": provider factory "+type.id()+" failed: "+error.getMessage(),error);}
    }
    public static synchronized Selection client(ResourceLocation dimension,ResourceLocation id,Profile profile,String settings,DynamicOps<JsonElement> ops){
        var type=TYPES.get(id);
        if(type==null||!type.clientBehavior())throw new JEDDefinitionException(dimension+": requires client provider "+id+"; install its matching addon on the client");
        if(type.profile()!=profile)throw new JEDDefinitionException(dimension+": client provider profile differs for "+id+"; install the matching addon version");
        return decode(type,dimension,JsonParser.parseString(settings),dimension+".provider",ops);
    }
    /** Configuration-phase presence check; native registry codecs/factories run later with play registries. */
    public static synchronized void requireClients(Map<ResourceLocation,Profile> requirements){
        for(var entry:new TreeMap<>(requirements).entrySet()){
            var type=TYPES.get(entry.getKey());
            if(type==null||!type.clientBehavior())throw justepicdimensions.JEDText.failure("provider.required",entry.getKey());
            if(type.profile()!=entry.getValue())throw justepicdimensions.JEDText.failure("provider.profile",entry.getKey());
        }
    }
    private JEDProviderFactoryRegistry() {}
}
