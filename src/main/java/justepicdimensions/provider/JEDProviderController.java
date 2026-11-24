package justepicdimensions.provider;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDProviderController {
    private static final class Owner {
        final Map<ResourceLocation,JEDProviderFactoryRegistry.Selection> definitions;
        volatile Map<Level,JEDWorldProvider> live;
        volatile Map<ResourceLocation,JEDWorldProvider> waiting;
        Owner(Map<ResourceLocation,JEDProviderFactoryRegistry.Selection> definitions,Map<Level,JEDWorldProvider> live,Map<ResourceLocation,JEDWorldProvider> waiting){this.definitions=Map.copyOf(definitions);this.live=Collections.unmodifiableMap(new IdentityHashMap<>(live));this.waiting=Map.copyOf(waiting);}
    }
    public static final class Prepared {private final Owner owner;private Prepared(Owner owner){this.owner=owner;}}
    public record ClientDefinition(String signature,justepicdimensions.lighting.JEDClientLightPolicy.Profile profile,JEDProviderSettings settings,JEDProviderFactoryRegistry.Selection custom){
        JEDWorldProvider fresh(ResourceLocation id){return custom==null?new JEDWorldProvider(profile,settings):custom.takePrepared();}
    }
    private static volatile Map<MinecraftServer,Owner> SERVERS=Map.of();
    private static final Map<ResourceLocation,ClientDefinition> CLIENT=new HashMap<>();
    private static final Map<Level,JEDWorldProvider> CLIENT_LEVELS=new IdentityHashMap<>();
    public static JEDWorldProvider policy(Level level){
        if(level.isClientSide()){bindClient(level);return CLIENT_LEVELS.get(level);}
        var owner=SERVERS.get(level.getServer());if(owner==null)return null;var bound=owner.live.get(level);return bound!=null?bound:owner.waiting.get(level.dimension().location());
    }
    public static boolean surface(boolean nativeValue,Level level){var p=policy(level);return p==null?nativeValue:p.surface(level);}
    public static boolean vaporizes(boolean nativeValue,Level level){var p=policy(level);return p==null?nativeValue:p.vaporizes(nativeValue,level);}
    public static int spawnHeight(int nativeValue,net.minecraft.world.level.LevelHeightAccessor height){if(height instanceof Level level){var p=policy(level);if(p!=null)return p.spawnHeight(nativeValue,height);}return nativeValue;}
    public static double horizon(double nativeValue,net.minecraft.world.level.LevelHeightAccessor height){if(height instanceof Level level){var p=policy(level);if(p!=null)return p.horizon(nativeValue,height);}return nativeValue;}
    public static net.minecraft.resources.ResourceKey<Level> mapSpinDimension(net.minecraft.resources.ResourceKey<Level> mapDimension,net.minecraft.world.level.LevelAccessor accessor){if(accessor instanceof Level level&&policy(level)!=null)return policy(level).surface(level)?null:mapDimension;return Level.NETHER;}
    /** Build all replacements before the definition revision is committed. */
    // Reads only loaded levels: getLevel loads on demand, so every definition commit (including each temporary reset)
    // reloaded every idle-unloaded provider dimension. Unloaded ones wait here and bind() attaches them on load.
    public static Prepared prepare(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions){
        var old=SERVERS.get(server);var selections=new HashMap<ResourceLocation,JEDProviderFactoryRegistry.Selection>();var live=new IdentityHashMap<Level,JEDWorldProvider>();var waiting=new HashMap<ResourceLocation,JEDWorldProvider>();
        definitions.forEach((id,d)->{var selection=d.provider();if(selection==null)return;selections.put(id,selection);var level=server.levels.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,id));
            JEDWorldProvider value=null;if(old!=null&&old.definitions.get(id)==selection)value=level==null?old.waiting.get(id):old.live.get(level);
            if(value==null)value=old!=null&&old.definitions.get(id)==selection?selection.fresh(id):selection.takePrepared();
            if(level==null)waiting.put(id,value);else live.put(level,value);
        });return new Prepared(new Owner(selections,live,waiting));
    }
    public static void publish(MinecraftServer server,Prepared prepared){var next=new IdentityHashMap<>(SERVERS);next.put(server,prepared.owner);SERVERS=Collections.unmodifiableMap(next);for(var level:server.getAllLevels())justepicdimensions.world.JEDWorldSync.changed(level);}
    public static void bind(ServerLevel level){var owner=SERVERS.get(level.getServer());if(owner==null||owner.live.containsKey(level))return;var id=level.dimension().location();var selection=owner.definitions.get(id);if(selection==null)return;var value=owner.waiting.get(id);if(value==null)value=selection.fresh(id);var live=new IdentityHashMap<>(owner.live);live.put(level,value);owner.live=Collections.unmodifiableMap(live);var waiting=new HashMap<>(owner.waiting);waiting.remove(id);owner.waiting=Map.copyOf(waiting);}
    public static void writeClient(ServerLevel level,net.minecraft.nbt.CompoundTag tag){var p=policy(level);if(p==null)return;tag.putString("jed_provider_profile",p.profile().name());tag.putString("jed_provider_settings",p.clientSettings().json().toString());var selection=SERVERS.get(level.getServer()).definitions.get(level.dimension().location());if(selection.type().clientBehavior()){tag.putString("jed_provider_client_type",selection.type().id().toString());tag.putString("jed_provider_client_settings",selection.settingsJson());}}
    public static ClientDefinition prepareClient(ResourceLocation id,net.minecraft.nbt.CompoundTag tag,com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops){
        if(!tag.contains("jed_provider_profile"))return null;String signature=tag.getString("jed_provider_profile")+'\n'+tag.getString("jed_provider_settings")+'\n'+tag.getString("jed_provider_client_type")+'\n'+tag.getString("jed_provider_client_settings");var old=CLIENT.get(id);if(old!=null&&old.signature().equals(signature))return old;
        var profile=justepicdimensions.lighting.JEDClientLightPolicy.Profile.valueOf(tag.getString("jed_provider_profile"));var settings=JEDProviderSettings.decode(com.google.gson.JsonParser.parseString(tag.getString("jed_provider_settings")),id+".provider.settings");var custom=tag.contains("jed_provider_client_type")?JEDProviderFactoryRegistry.client(id,ResourceLocation.parse(tag.getString("jed_provider_client_type")),profile,tag.getString("jed_provider_client_settings"),ops):null;
        return new ClientDefinition(signature,profile,settings,custom);
    }
    public static void client(ResourceLocation id,ClientDefinition definition,Level current){var previous=definition==null?CLIENT.remove(id):CLIENT.put(id,definition);if(previous!=definition&&current!=null&&current.dimension().location().equals(id)){if(definition==null)CLIENT_LEVELS.remove(current);else CLIENT_LEVELS.put(current,definition.fresh(id));}}
    public static void bindClient(Level level){var d=CLIENT.get(level.dimension().location());if(d!=null&&!CLIENT_LEVELS.containsKey(level))CLIENT_LEVELS.put(level,d.fresh(level.dimension().location()));}
    public static void clearClient(){CLIENT.clear();CLIENT_LEVELS.clear();}
    public static Map<ResourceLocation,justepicdimensions.lighting.JEDClientLightPolicy.Profile> clientRequirements(MinecraftServer server){
        var result=new TreeMap<ResourceLocation,justepicdimensions.lighting.JEDClientLightPolicy.Profile>();var owner=SERVERS.get(server);if(owner!=null)owner.definitions.forEach((id,selection)->{if(selection.type().clientBehavior()&&justepicdimensions.dimension.JEDDimensionLifecycle.enabled(server,id))result.put(selection.type().id(),selection.type().profile());});return Map.copyOf(result);
    }
    @SubscribeEvent public static void unload(net.neoforged.neoforge.event.level.LevelEvent.Unload event){if(event.getLevel() instanceof Level level){if(level.isClientSide())CLIENT_LEVELS.remove(level);else{var owner=SERVERS.get(level.getServer());if(owner!=null){var live=new IdentityHashMap<>(owner.live);live.remove(level);owner.live=Collections.unmodifiableMap(live);}}}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var next=new IdentityHashMap<>(SERVERS);next.remove(event.getServer());SERVERS=Collections.unmodifiableMap(next);}
    private JEDProviderController(){}
}
