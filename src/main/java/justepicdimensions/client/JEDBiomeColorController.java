package justepicdimensions.client;

import java.util.*;
import justepicdimensions.render.JEDRenderDefinition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;

/** Compile selectors on policy changes, then read immutable palettes from meshing workers. */
public final class JEDBiomeColorController {
    private record Palette(Map<Biome,Integer> grass,Map<Biome,Integer> foliage,Map<Biome,Integer> water,Map<ColorResolver,ColorResolver> resolvers) {}
    private static volatile Map<ResourceLocation,Palette> palettes=Map.of();
    public static void bind(ClientLevel level,JEDRenderDefinition policy){
        var next=new HashMap<>(palettes);var id=level.dimension().location();
        if(!justepicdimensions.lighting.JEDLightingConfig.colors()||policy==null||policy.biomeColors().isEmpty())next.remove(id);
        else next.put(id,new Palette(compile(level,policy.biomeColors().getOrDefault("grass",List.of())),compile(level,policy.biomeColors().getOrDefault("foliage",List.of())),compile(level,policy.biomeColors().getOrDefault("water",List.of())),next.containsKey(id)?next.get(id).resolvers():resolvers(id)));palettes=Map.copyOf(next);
    }
    private static Map<Biome,Integer> compile(ClientLevel level,List<JEDRenderDefinition.BiomeRule> rules){
        return justepicdimensions.render.JEDBiomeColorPalette.compile(level.registryAccess().registryOrThrow(Registries.BIOME),rules);
    }
    public static int color(ColorResolver resolver,Biome biome,double x,double z,ClientLevel level){
        return color(resolver,biome,x,z,level.dimension().location());
    }
    private static int color(ColorResolver resolver,Biome biome,double x,double z,ResourceLocation dimension){
        var palette=palettes.get(dimension);if(palette!=null){var colors=resolver==BiomeColors.GRASS_COLOR_RESOLVER?palette.grass():resolver==BiomeColors.FOLIAGE_COLOR_RESOLVER?palette.foliage():resolver==BiomeColors.WATER_COLOR_RESOLVER?palette.water():null;if(colors!=null){var override=colors.get(biome);if(override!=null)return override;}}
        return resolver.getColor(biome,x,z);
    }
    private static Map<ColorResolver,ColorResolver> resolvers(ResourceLocation dimension){
        var out=new IdentityHashMap<ColorResolver,ColorResolver>();
        for(var nativeResolver:List.of(BiomeColors.GRASS_COLOR_RESOLVER,BiomeColors.FOLIAGE_COLOR_RESOLVER,BiomeColors.WATER_COLOR_RESOLVER))out.put(nativeResolver,(biome,x,z)->color(nativeResolver,biome,x,z,dimension));
        return Collections.unmodifiableMap(out);
    }
    /** Stable resolver identities keep optimized caches bounded across policy/tag updates. */
    public static ColorResolver resolver(ColorResolver original,ClientLevel level){
        var palette=palettes.get(level.dimension().location());return palette==null?original:palette.resolvers().getOrDefault(original,original);
    }
    public static void clear(){palettes=Map.of();}
    private JEDBiomeColorController(){}
}
