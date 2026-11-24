package justepicdimensions.provider;

import justepicdimensions.definition.JEDDimensionDefinition;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.*;
import net.minecraft.world.level.*;

/** Executable per-level provider; engine consumers ask only for the property they need. */
public class JEDWorldProvider {
    private final Profile profile;
    private final JEDProviderSettings settings;
    public JEDWorldProvider(Profile profile,JEDProviderSettings settings){this.profile=Objects.requireNonNull(profile);this.settings=Objects.requireNonNull(settings);}
    public final Profile profile(){return profile;}
    public JEDProviderSettings settings(){return settings;}
    /** Standard client projection for a server-only addon; no addon classes are sent over the wire. */
    public JEDProviderSettings clientSettings(){return settings();}
    public static JEDWorldProvider of(JEDDimensionDefinition definition) {
        if(definition==null||definition.provider()==null)return null;
        return definition.provider().prepared();
    }
    public boolean surface(){return settings().surfaceWorld()!=null?settings().surfaceWorld():profile==Profile.SURFACE||profile==Profile.BASE;}
    public boolean vaporizes(boolean nativeValue){return settings().waterVaporizes()!=null?settings().waterVaporizes():profile==Profile.NETHER||nativeValue;}
    public boolean surface(Level level){return surface();}
    public boolean vaporizes(boolean nativeValue,Level level){return vaporizes(nativeValue);}
    public int spawnHeight(int nativeValue,LevelHeightAccessor height){return settings().spawnHeight()==null?nativeValue:settings().spawnHeight();}
    public double horizon(double nativeValue,LevelHeightAccessor height){return settings().horizon()==null?nativeValue:settings().horizon();}
    public boolean fog(boolean nativeValue,Level level,BlockPos camera){var s=settings();
        if(s.biomeFog()){var key=level.getBiome(camera).unwrapKey();boolean listed=key.isPresent()&&s.foggyBiomes().contains(key.get().location());return listed==s.listedHaveFog();}
        return s.fog()!=null?s.fog():profile==Profile.NETHER;
    }
    public Music music(Music original,Level level,BlockPos position,boolean boss){
        if(profile==Profile.END)return boss?Musics.END_BOSS:Musics.END;
        if(profile==Profile.NETHER&&!original.getEvent().equals(Musics.UNDER_WATER.getEvent()))return level.getBiome(position).value().getBackgroundMusic().orElseGet(()->Musics.createGameMusic(SoundEvents.MUSIC_BIOME_NETHER_WASTES));
        return original;
    }
}
