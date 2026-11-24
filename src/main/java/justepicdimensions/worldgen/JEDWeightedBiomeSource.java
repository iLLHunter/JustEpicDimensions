package justepicdimensions.worldgen;

import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.lang.ref.*;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;

/** Immutable, seed-aware regions. A sampler belongs to one RandomState, never to a shared biome source. */
public final class JEDWeightedBiomeSource extends BiomeSource {
    public record Entry(Holder<Biome> biome,int weight) {
        public static final Codec<Entry> CODEC=RecordCodecBuilder.create(i->i.group(Biome.CODEC.fieldOf("biome").forGetter(Entry::biome),Codec.intRange(1,Integer.MAX_VALUE).fieldOf("weight").forGetter(Entry::weight)).apply(i,Entry::new));
    }
    public record Band(int minY,int maxY,List<Entry> entries) {
        public Band {entries=List.copyOf(entries);}
        public static final Codec<Band> CODEC=RecordCodecBuilder.create(i->i.group(Codec.INT.fieldOf("min_y").forGetter(Band::minY),Codec.INT.fieldOf("max_y").forGetter(Band::maxY),Entry.CODEC.listOf().fieldOf("entries").forGetter(Band::entries)).apply(i,Band::new));
    }
    public static final MapCodec<JEDWeightedBiomeSource> CODEC=RecordCodecBuilder.<JEDWeightedBiomeSource>mapCodec(i->i.group(
        Entry.CODEC.listOf().fieldOf("entries").forGetter(s->s.entries),
        Codec.intRange(1,Integer.MAX_VALUE).fieldOf("region_size").forGetter(s->s.size),
        Codec.STRING.optionalFieldOf("shape","irregular").forGetter(s->s.shape),
        Band.CODEC.listOf().optionalFieldOf("height_bands",List.of()).forGetter(s->s.bands)
    ).apply(i,(entries,size,shape,bands)->new JEDWeightedBiomeSource(entries,size,shape,bands,false))).validate(s->{try{s.validate();return DataResult.success(s);}catch(IllegalArgumentException e){return DataResult.error(e::getMessage);}});
    private final List<Entry> entries;private final int size;private final String shape;private final List<Band> bands;
    private record Table(List<Entry> entries,long[] ends,long total){static Table of(List<Entry> entries){long[] ends=new long[entries.size()];long total=0;for(int i=0;i<entries.size();i++)ends[i]=total=Math.addExact(total,entries.get(i).weight);return new Table(entries,ends,total);}Holder<Biome> pick(long hash){long ticket=Long.remainderUnsigned(hash,total)+1;int index=Arrays.binarySearch(ends,ticket);return entries.get(index<0?-index-1:index).biome;}}
    private record HeightTable(int minY,int maxY,Table table){}
    private final Table main;private final List<HeightTable> heights;
    public JEDWeightedBiomeSource(List<Entry> entries,int size,String shape,List<Band> bands){this(entries,size,shape,bands,true);}
    private JEDWeightedBiomeSource(List<Entry> entries,int size,String shape,List<Band> bands,boolean check){this.entries=List.copyOf(entries);this.size=size;this.shape=shape;this.bands=List.copyOf(bands);if(check)validate();main=Table.of(this.entries);heights=this.bands.stream().sorted(Comparator.comparingInt(Band::minY)).map(b->new HeightTable(b.minY,b.maxY,Table.of(b.entries))).toList();}
    private void validate(){if(size<1||entries.isEmpty())throw new IllegalArgumentException("Weighted biomes require positive region_size and a nonempty main biome list");if(!Set.of("irregular","grid").contains(shape))throw new IllegalArgumentException("Biome shape must be irregular or grid");for(var e:entries)if(e.weight<1)throw new IllegalArgumentException("Biome weights must be positive");var sorted=bands.stream().sorted(Comparator.comparingInt(Band::minY)).toList();for(int i=0;i<sorted.size();i++){var b=sorted.get(i);if(b.minY>=b.maxY||b.entries.isEmpty()||b.entries.stream().anyMatch(e->e.weight<1))throw new IllegalArgumentException("Height bands require min_y < max_y and positive, nonempty biome weights");if(i>0&&sorted.get(i-1).maxY>b.minY)throw new IllegalArgumentException("Weighted biome height bands must not overlap");}}
    @Override protected MapCodec<? extends BiomeSource> codec(){return CODEC;}
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes(){return Stream.concat(entries.stream(),bands.stream().flatMap(b->b.entries.stream())).map(Entry::biome).distinct();}
    @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler){return sample(x*4L,y*4L,z*4L,seed(sampler));}
    public Holder<Biome> sample(long x,long y,long z,long seed){
        long cx=Math.floorDiv(x,size),cz=Math.floorDiv(z,size),rx=cx,rz=cz;
        if(shape.equals("irregular")){double best=Double.POSITIVE_INFINITY;for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){long nx=cx+dx,nz=cz+dz,h=hash(seed,nx,nz);double px=(nx+unit(h))*size,pz=(nz+unit(mix(h)))*size,d=(px-x)*(px-x)+(pz-z)*(pz-z);if(d<best){best=d;rx=nx;rz=nz;}}}
        Table selected=main;int low=0,high=heights.size()-1;while(low<=high){int middle=(low+high)>>>1;var band=heights.get(middle);if(y<band.minY)high=middle-1;else if(y>=band.maxY)low=middle+1;else{selected=band.table;break;}}
        return selected.pick(hash(seed^0x6a09e667f3bcc909L,rx,rz));
    }
    private static double unit(long v){return (v>>>11)*0x1.0p-53;}
    private static long hash(long seed,long x,long z){return mix(seed^mix(x)^Long.rotateLeft(mix(z),27));}
    private static long mix(long n){n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
    private static final ReferenceQueue<Climate.Sampler> QUEUE=new ReferenceQueue<>();
    private static final java.util.concurrent.ConcurrentMap<SamplerKey,Long> SEEDS=new java.util.concurrent.ConcurrentHashMap<>();
    private record CachedSeed(SamplerKey sampler,long seed){}
    private static final ThreadLocal<CachedSeed> CACHED_SEED=new ThreadLocal<>();
    private static final class SamplerKey extends WeakReference<Climate.Sampler>{final int hash;SamplerKey(Climate.Sampler s,boolean queued){super(s,queued?QUEUE:null);hash=System.identityHashCode(s);}public int hashCode(){return hash;}public boolean equals(Object o){return this==o||o instanceof SamplerKey k&&get()!=null&&get()==k.get();}}
    public static void bind(Climate.Sampler sampler,long seed){Reference<? extends Climate.Sampler> gone;while((gone=QUEUE.poll())!=null)SEEDS.remove(gone);var key=new SamplerKey(sampler,true);var old=SEEDS.putIfAbsent(key,seed);if(old!=null&&old!=seed)throw new IllegalStateException("Cannot bind one biome sampler to different dimension seeds");}
    /** Noise chunks wrap the router in a new sampler; preserve its owning RandomState's seed. */
    public static Climate.Sampler chunkSampler(Climate.Sampler sampler,net.minecraft.world.level.levelgen.RandomState state){
        var seed=SEEDS.get(new SamplerKey(state.sampler(),false));
        if(seed!=null)bind(sampler,seed);
        return sampler;
    }
    private static long seed(Climate.Sampler sampler){var cached=CACHED_SEED.get();if(cached!=null&&cached.sampler.get()==sampler)return cached.seed;var key=new SamplerKey(sampler,false);var seed=SEEDS.get(key);if(seed==null)throw new IllegalStateException("Weighted biome sampling requires its dimension's seeded RandomState");CACHED_SEED.set(new CachedSeed(key,seed));return seed;}
}
