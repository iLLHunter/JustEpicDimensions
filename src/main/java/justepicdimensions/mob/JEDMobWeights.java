package justepicdimensions.mob;

import java.math.*;
import java.lang.ref.*;
import java.util.*;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;

/** Preserve native integer lists when unchanged; rescale fractional/large authored weights safely. */
public final class JEDMobWeights {
    private record Entry(net.minecraft.world.entity.EntityType<?> type,int weight,int min,int max){}
    private static final ReferenceQueue<MobSpawnSettings.SpawnerData> EXPIRED=new ReferenceQueue<>();
    private static final Map<Entry,EntryReference> ENTRIES=new HashMap<>();
    private static final class EntryReference extends WeakReference<MobSpawnSettings.SpawnerData>{
        final Entry key;EntryReference(Entry key,MobSpawnSettings.SpawnerData value){super(value,EXPIRED);this.key=key;}
    }
    /** NaturalSpawner re-queries the list and tests the selected object by identity.
     * Keep live adapted entries stable without retaining obsolete mod-provided lists. */
    private static synchronized MobSpawnSettings.SpawnerData entry(net.minecraft.world.entity.EntityType<?> type,int weight,int min,int max){
        EntryReference expired;while((expired=(EntryReference)EXPIRED.poll())!=null)ENTRIES.remove(expired.key,expired);
        var key=new Entry(type,weight,min,max);var reference=ENTRIES.get(key);var value=reference==null?null:reference.get();
        if(value==null){value=new MobSpawnSettings.SpawnerData(type,weight,min,max);ENTRIES.put(key,new EntryReference(key,value));}return value;
    }
    public static List<MobSpawnSettings.SpawnerData> apply(JEDMobSpawnPolicy policy,MobCategory category,JEDMobSpawnSource source,List<MobSpawnSettings.SpawnerData> original){
        if(!policy.changesLists(source))return original;
        var candidates=new ArrayList<>(original);
        if(policy.sources().contains(source))for(var a:policy.additions())if(a.entity().getCategory()==category)candidates.add(entry(a.entity(),a.weight(),a.minCount(),a.maxCount()));
        var kept=new ArrayList<MobSpawnSettings.SpawnerData>();var values=new ArrayList<BigDecimal>();var total=BigDecimal.ZERO;int scale=0;
        for(var candidate:candidates){
            if(!policy.permits(candidate.type,source))continue;
            var multiplier=policy.multiplier(candidate.type,source);if(multiplier.signum()==0)continue;
            var value=BigDecimal.valueOf(candidate.getWeight().asInt()).multiply(multiplier,MathContext.DECIMAL128).stripTrailingZeros();
            kept.add(candidate);values.add(value);total=total.add(value,MathContext.DECIMAL128);scale=Math.max(scale,value.scale());
        }
        if(total.signum()==0)return kept.equals(original)?original:List.copyOf(kept);
        var budget=BigDecimal.valueOf(Integer.MAX_VALUE-kept.size());
        var factor=BigDecimal.ONE.scaleByPowerOfTen(Math.max(0,scale)).min(budget.divide(total,MathContext.DECIMAL128));
        var result=new ArrayList<MobSpawnSettings.SpawnerData>();
        for(int i=0;i<kept.size();i++){
            var entry=kept.get(i);var raw=values.get(i);int weight=raw.signum()==0?0:Math.max(1,raw.multiply(factor,MathContext.DECIMAL128).setScale(0,RoundingMode.HALF_UP).intValueExact());
            result.add(weight==entry.getWeight().asInt()?entry:entry(entry.type,weight,entry.minCount,entry.maxCount));
        }
        return result.equals(original)?original:List.copyOf(result);
    }
    private JEDMobWeights(){}
}
