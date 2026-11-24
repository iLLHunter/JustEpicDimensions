package justepicdimensions.render;

import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.JEDDimensionDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Common-side sunlight behavior and immutable per-dimension policy publication. */
@EventBusSubscriber(modid=JustEpicDimensions.ID)
public final class JEDRenderController {
    private static volatile Map<MinecraftServer,Map<ResourceLocation,JEDRenderDefinition>> servers=Map.of();
    private static volatile Map<ResourceLocation,JEDRenderDefinition> clients=Map.of();
    public static JEDRenderDefinition policy(Level level){if(level.dimension()==null)return null;if(level.isClientSide())return clients.get(level.dimension().location());var server=level.getServer();return server==null?null:servers.getOrDefault(server,Map.of()).get(level.dimension().location());}
    public static JEDRenderDefinition client(ResourceLocation id){return clients.get(id);}
    public static void client(ResourceLocation id,JEDRenderDefinition value){var next=new HashMap<>(clients);if(value==null)next.remove(id);else next.put(id,value);clients=Map.copyOf(next);}
    public static void clearClient(){clients=Map.of();}
    public static void publish(MinecraftServer server,Map<ResourceLocation,JEDDimensionDefinition> definitions){var entries=new HashMap<ResourceLocation,JEDRenderDefinition>();definitions.forEach((id,d)->{var render=d.render();if(render!=null)entries.put(id,render);});var next=new IdentityHashMap<>(servers);next.put(server,Map.copyOf(entries));servers=Collections.unmodifiableMap(next);}
    public static int skyDarken(int nativeValue,Level level){var p=policy(level);return p!=null&&p.sky().brightnessFactor()!=null?(int)((1-p.sky().brightnessFactor())*11):nativeValue;}

    /*
     * Sky effects as other mods see them. Shader loaders and sky mods choose their sky from DimensionType.effectsLocation
     * (Iris picks a pack's End or overworld shaders by it), but JED's sky mode replaces only the rendered effects. The
     * active client level whose sky mode differs from its dimension type's effects reports a copy of its dimension type
     * with the matching effects; every other field is its own, and server levels keep the native type. Level.dimensionType()
     * is called constantly (build heights, lighting), so the hook is a single identity check.
     */
    private record SkyEffects(Level level,net.minecraft.world.level.dimension.DimensionType type){}
    private static volatile SkyEffects skyEffects;
    public static net.minecraft.world.level.dimension.DimensionType dimensionType(net.minecraft.world.level.dimension.DimensionType original,Level level){
        var s=skyEffects;return s!=null&&level==s.level()?s.type():original;
    }
    /** Set by the client runtime when the active level or its policy changes; null clears it. */
    public static void skyEffects(Level level){
        var p=level==null?null:policy(level);
        var effects=p==null?null:switch(p.sky().mode()){
            case END->net.minecraft.world.level.dimension.BuiltinDimensionTypes.END_EFFECTS;
            case OVERWORLD->net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD_EFFECTS;
            default->null;
        };
        var nativeType=level==null?null:level.dimensionTypeRegistration().value();
        if(effects==null||nativeType==null||effects.equals(nativeType.effectsLocation())){skyEffects=null;return;}
        var t=nativeType;
        skyEffects=new SkyEffects(level,new net.minecraft.world.level.dimension.DimensionType(t.fixedTime(),t.hasSkyLight(),t.hasCeiling(),t.ultraWarm(),
            t.natural(),t.coordinateScale(),t.bedWorks(),t.respawnAnchorWorks(),t.minY(),t.height(),t.logicalHeight(),t.infiniburn(),effects,t.ambientLight(),
            t.monsterSettings()));
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var next=new IdentityHashMap<>(servers);next.remove(event.getServer());servers=Collections.unmodifiableMap(next);}
    private JEDRenderController(){}
}
