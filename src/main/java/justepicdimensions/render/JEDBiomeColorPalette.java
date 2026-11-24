package justepicdimensions.render;

import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** Compile a snapshot after tag binding; meshing workers read the immutable result. */
public final class JEDBiomeColorPalette {
    public static Map<Biome,Integer> compile(Registry<Biome> registry,List<JEDRenderDefinition.BiomeRule> rules){
        var colors=new IdentityHashMap<Biome,Integer>();var holders=registry.holders().toList();
        for(var rule:rules)for(var holder:holders){
            var id=holder.key().location();boolean matches=rule.biome()!=null?rule.biome().equals(id):rule.tag()!=null?holder.is(TagKey.create(Registries.BIOME,rule.tag())):rule.pattern().matcher(id.toString()).matches();
            if(matches)colors.put(holder.value(),rule.color().rgb());
        }return Collections.unmodifiableMap(colors);
    }
    private JEDBiomeColorPalette(){}
}
