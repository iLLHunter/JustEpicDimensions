package justepicdimensions.lighting;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

/** Exact lifetime, geometry generation, receipt, consumption and publication state. */
public final class JEDClientLightTracker implements AutoCloseable {
    public final JEDClientLightView view;
    public final JEDClientLightScheduler scheduler;
    final Long2ObjectLinkedOpenHashMap<Chunk> chunks = new Long2ObjectLinkedOpenHashMap<>();
    private final IdentityHashMap<Object, ArrayList<Publication>> consumed = new IdentityHashMap<>();
    private final Set<Chunk> blockActivations=new HashSet<>(),skyActivations=new HashSet<>();
    final Thread thread = Thread.currentThread();
    JEDClientLightPolicy policy;
    long serial, epoch, completed, created, cancelled, receipts, publications, removals, imports, duplicates, generations;
    boolean closed;
    Object blockStorage,skyStorage;
    private long schedulingSerial;
    private boolean nearbyTurn=true;

    static final class Section {
        final long[] received = new long[2], applied = new long[2], revision = new long[2], published = new long[2], token = new long[2];
        final DataLayer[] data = new DataLayer[2];
        boolean ready() { return received[0] <= applied[0] && received[1] <= applied[1]
            && revision[0] == published[0] && revision[1] == published[1]; }
    }
    static final class Chunk {
        final int x, z;
        final Section[] sections;
        long generation;
        long visited;
        long lastPriority;
        long activationReceipt,activatedReceipt;
        boolean blockActivation,skyActivation;
        boolean geometryReady, sawFull, sawUpdate;
        JEDClientLightJob job;
        Chunk(int x, int z, int count) {
            this.x = x; this.z = z; sections = new Section[count + 2];
            Arrays.setAll(sections, i -> new Section());
        }
    }
    public static final class Receipt {
        final JEDClientLightTracker tracker;
        final Chunk chunk;
        final long id, generation;
        final boolean full;
        final BitSet sky, block;
        Receipt(JEDClientLightTracker t, Chunk c, long id, boolean full, BitSet sky, BitSet block) {
            tracker=t;chunk=c;this.id=id;generation=c.generation;this.full=full;
            this.sky=(BitSet)sky.clone();this.block=(BitSet)block.clone();
        }
        boolean current() { return !tracker.closed && tracker.chunks.get(ChunkPos.asLong(chunk.x, chunk.z)) == chunk && generation == chunk.generation; }
    }
    private record Publication(Chunk chunk, long generation, int index, int layer, long revision) {}

    public JEDClientLightTracker(JEDClientLightView view, JEDClientLightScheduler.Budget budget) {
        this.view=view; scheduler=new JEDClientLightScheduler(this,budget);
    }
    /** Change ceilings without losing pending jobs, receipts or the current tick's spent allowance. */
    public void budget(JEDClientLightScheduler.Budget next) {
        assertThread();if(!closed)scheduler.budget=Objects.requireNonNull(next);
    }
    void assertThread() {
        if (Thread.currentThread()!=thread) throw new IllegalStateException("JED client light state accessed off its registered thread");
    }
    public void policy(JEDClientLightPolicy next) {
        assertThread(); if (closed || next==policy) return;
        var previous=policy; policy=next; epoch++;
        for (var c:chunks.values()) {
            if (next==null || next==JEDClientLightPolicy.OFF) { if(c.job!=null){c.job=null;cancelled++;} }
            else if (previous==JEDClientLightPolicy.OFF) request(c);
            else if (previous==null && (c.sawUpdate || next.accepts(true)&&c.sawFull)) request(c);
        }
    }
    public JEDClientLightPolicy policy() { return policy; }
    public void loaded(int x,int z) {
        assertThread();if(closed)return;
        var c=chunks.computeIfAbsent(ChunkPos.asLong(x,z), k -> new Chunk(x,z,view.sectionCount()));
        c.geometryReady=true;epoch++;
    }
    public void unload(int x,int z) {
        assertThread();var c=chunks.remove(ChunkPos.asLong(x,z));
        if(c!=null){if(c.job!=null)cancelled++;epoch++;}
        blockActivations.remove(c);skyActivations.remove(c);
        // Drop references held by a partially consumed drain as well.
        for(var list:consumed.values())list.removeIf(p -> p.chunk==c);
    }
    public Receipt receipt(int x,int z,boolean full,BitSet sky,BitSet block) {
        assertThread(); if(closed)return null;
        long key=ChunkPos.asLong(x,z);var c=chunks.get(key);
        if(c==null){
            if(!full && view.chunk(x,z)==null)return null;
            c=new Chunk(x,z,view.sectionCount());chunks.put(key,c);
            c.geometryReady=view.chunk(x,z)!=null;
        }
        if(full){
            if(c.job!=null)cancelled++;
            c.job=null;c.generation++;generations++;c.geometryReady=false;
            c.activatedReceipt=0;c.blockActivation=c.skyActivation=false;blockActivations.remove(c);skyActivations.remove(c);
            Arrays.setAll(c.sections, i->new Section());c.sawFull=true;
        }else if(!sky.isEmpty()||!block.isEmpty())c.sawUpdate=true;
        var r=new Receipt(this,c,++serial,full,sky,block);receipts++;epoch++;
        if(full)c.activationReceipt=r.id;
        markReceipt(r,0,block);markReceipt(r,1,sky);
        if(policy!=null&&policy.accepts(full)&&(full||!sky.isEmpty()||!block.isEmpty()))request(c);
        return r;
    }
    private void markReceipt(Receipt r,int layer,BitSet mask){
        for(int i=mask.nextSetBit(0);i>=0&&i<r.chunk.sections.length;i=mask.nextSetBit(i+1)){
            r.chunk.sections[i].received[layer]=r.id;invalidate(r.chunk,i-1);
        }
    }
    public void geometryApplied(Receipt r) {
        assertThread();if(r!=null&&r.current()){r.chunk.geometryReady=view.chunk(r.chunk.x,r.chunk.z)!=null;epoch++;}
    }
    public void applied(Receipt r) {
        assertThread();if(r==null||!r.current())return;
        for(int layer=0;layer<2;layer++){
            var mask=layer==0?r.block:r.sky;
            for(int i=mask.nextSetBit(0);i>=0&&i<r.chunk.sections.length;i=mask.nextSetBit(i+1))
                r.chunk.sections[i].applied[layer]=Math.max(r.chunk.sections[i].applied[layer],r.id);
        }
        r.chunk.geometryReady=view.chunk(r.chunk.x,r.chunk.z)!=null;epoch++;
        if(r.full){
            r.chunk.activatedReceipt=r.id;
            if(view.engine().getLayerListener(LightLayer.BLOCK)!=net.minecraft.world.level.lighting.LayerLightEventListener.DummyLightLayerEventListener.INSTANCE){r.chunk.blockActivation=true;blockActivations.add(r.chunk);}
            if(view.engine().getLayerListener(LightLayer.SKY)!=net.minecraft.world.level.lighting.LayerLightEventListener.DummyLightLayerEventListener.INSTANCE){r.chunk.skyActivation=true;skyActivations.add(r.chunk);}
        }
    }
    public void queued(LightLayer light,SectionPos pos,DataLayer data,Receipt origin) {
        assertThread();if(closed)return;
        var c=chunks.get(ChunkPos.asLong(pos.x(),pos.z()));if(c==null)return;
        if(origin!=null&&origin.tracker==this&&origin.chunk==c&&!origin.current())return;
        if(origin!=null&&(origin.tracker!=this||origin.chunk!=c))origin=null;
        int i=pos.y()-view.minSection()+1;if(i<0||i>=c.sections.length)return;
        int layer=light==LightLayer.BLOCK?0:1;var s=c.sections[i];
        long token=origin==null?++serial:origin.id;
        if(s.token[layer]==token&&s.data[layer]==data){duplicates++;return;}
        s.token[layer]=token;s.data[layer]=data;s.revision[layer]++;
        if(data==null){s.published[layer]=s.revision[layer];removals++;epoch++;return;}
        imports++;
        if(origin==null){c.sawUpdate=true;if(policy!=null&&policy.accepts(false))request(c);}
        invalidate(c,i-1);epoch++;
    }
    public void consumed(Object storage,LightLayer light,long section,DataLayer data) {
        assertThread();var c=chunks.get(ChunkPos.asLong(SectionPos.x(section),SectionPos.z(section)));
        if(c==null)return;int i=SectionPos.y(section)-view.minSection()+1;
        if(i<0||i>=c.sections.length)return;
        int layer=light==LightLayer.BLOCK?0:1;var s=c.sections[i];
        if(s.data[layer]!=data)return;
        consumed.computeIfAbsent(storage,k->new ArrayList<>()).add(new Publication(c,c.generation,i,layer,s.revision[layer]));
    }
    public void published(Object storage,LightLayer layer) {
        assertThread();var activations=layer==LightLayer.BLOCK?blockActivations:skyActivations;
        if(!activations.isEmpty()){
            for(var c:activations){if(layer==LightLayer.BLOCK)c.blockActivation=false;else c.skyActivation=false;}
            activations.clear();epoch++;
        }
        var list=consumed.remove(storage);if(list==null)return;
        for(var p:list){
            if(chunks.get(ChunkPos.asLong(p.chunk.x,p.chunk.z))!=p.chunk||p.chunk.generation!=p.generation)continue;
            var s=p.chunk.sections[p.index];if(s.revision[p.layer]!=p.revision)continue;
            s.published[p.layer]=p.revision;s.data[p.layer]=null;publications++;epoch++;
        }
    }
    private void request(Chunk c) {
        if(c.job==null){c.job=new JEDClientLightJob(this,c);created++;}
    }
    private void invalidate(Chunk c,int buildIndex) {
        if(c.job!=null)c.job.invalidate(buildIndex);
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
            var n=chunks.get(ChunkPos.asLong(c.x+dx,c.z+dz));
            if(n!=null&&n.job!=null)n.job.borderChanged(buildIndex,dx,dz);
        }
    }
    boolean ready(Chunk c,int sy) {
        if(c==null||!c.geometryReady||c.activationReceipt!=c.activatedReceipt||c.blockActivation||c.skyActivation||view.chunk(c.x,c.z)==null)return false;
        int index=sy-view.minSection()+1;
        for(int i=Math.max(0,index-1);i<=Math.min(c.sections.length-1,index+1);i++)if(!c.sections[i].ready())return false;
        return true;
    }
    boolean readyAt(Chunk c,int sy,int x,int z) {
        if(!ready(c,sy))return false;
        int xmin=(x&15)==0?-1:0,xmax=(x&15)==15?1:0;
        int zmin=(z&15)==0?-1:0,zmax=(z&15)==15?1:0;
        for(int dx=xmin;dx<=xmax;dx++)for(int dz=zmin;dz<=zmax;dz++)
            if((dx!=0||dz!=0)&&!ready(chunks.get(ChunkPos.asLong(c.x+dx,c.z+dz)),sy))return false;
        return true;
    }
    Chunk nextChunk() {
        if(chunks.isEmpty())return null;
        schedulingSerial++;Chunk chosen=null;double best=-1;
        if(nearbyTurn){
            int px=view.playerChunkX(),pz=view.playerChunkZ();
            for(var c:chunks.values())if(c.job!=null&&c.geometryReady&&c.job.mayRun()){
                double dx=(double)c.x-px,dz=(double)c.z-pz;
                double score=(double)(schedulingSerial-c.lastPriority)/(1+dx*dx+dz*dz);
                if(score>best){best=score;chosen=c;}
            }
        }
        nearbyTurn=!nearbyTurn;
        if(chosen==null){long k=chunks.firstLongKey();chosen=chunks.removeFirst();chunks.put(k,chosen);}
        else {chosen.lastPriority=schedulingSerial;chunks.getAndMoveToLast(ChunkPos.asLong(chosen.x,chosen.z));}
        return chosen;
    }
    boolean hasReadyWork() { for(var c:chunks.values())if(c.job!=null&&c.geometryReady&&c.job.mayRun())return true;return false; }
    public boolean pending() { for(var c:chunks.values())if(c.job!=null)return true;return false; }
    public boolean eligibleWork() { return hasReadyWork(); }
    public long visited(int x,int z){var c=chunks.get(ChunkPos.asLong(x,z));return c==null?0:c.visited;}
    public boolean canCheck(net.minecraft.core.BlockPos position) {
        return readyAt(chunks.get(ChunkPos.asLong(position.getX()>>4,position.getZ()>>4)),position.getY()>>4,position.getX(),position.getZ());
    }
    public void tick(long value){assertThread();scheduler.tick(value);}
    public Map<String,Object> diagnostics() {
        int jobs=0,sections=0,borders=0,pendingImports=0,pendingActivation=0;
        for(var c:chunks.values()){
            if(c.job!=null){jobs++;sections+=c.job.remainingSections();borders+=c.job.waitingBorders();}
            for(var s:c.sections)if(!s.ready())pendingImports++;
            if(c.activationReceipt!=c.activatedReceipt||c.blockActivation||c.skyActivation)pendingActivation++;
        }
        var out=new LinkedHashMap<String,Object>(scheduler.diagnostics());
        out.put("loaded_chunks",chunks.size());out.put("jobs",jobs);out.put("pending_sections",sections);
        out.put("waiting_border_positions",borders);out.put("waiting_import_sections",pendingImports);
        out.put("created",created);out.put("completed",completed);out.put("cancelled",cancelled);
        out.put("receipts",receipts);out.put("publications",publications);out.put("removals",removals);
        out.put("imports",imports);out.put("duplicate_notifications",duplicates);out.put("full_geometry_generations",generations);
        out.put("waiting_activation_chunks",pendingActivation);return out;
    }
    @Override public void close(){assertThread();for(var c:chunks.values())if(c.job!=null)cancelled++;closed=true;chunks.clear();consumed.clear();blockActivations.clear();skyActivations.clear();epoch++;}
}
