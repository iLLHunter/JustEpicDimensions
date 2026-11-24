package justepicdimensions.lighting;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

/** Finite section cursors and compact deferred border bits; never a whole-job position set. */
public final class JEDClientLightJob {
    private static final int[] DX={0,0,-1,1,0,0}, DY={-1,1,0,0,0,0}, DZ={0,0,0,0,-1,1};
    private final JEDClientLightTracker tracker;
    private final JEDClientLightTracker.Chunk chunk;
    private final Work[] sections;
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos(), adjacent=new BlockPos.MutableBlockPos();
    private final long[] pending=new long[7];
    private int pendingIndex,pendingCount,pendingSection=-1,sectionCursor;
    private long waitingEpoch=Long.MIN_VALUE;
    private boolean preferPriority=true;
    private static final class Work {
        int cursor;
        boolean classified,full,priority;
        final BitSet deferred=new BitSet();
        long retriedEpoch=Long.MIN_VALUE;
        int retryCursor;
        boolean done(){return cursor>=4096&&deferred.isEmpty();}
    }
    JEDClientLightJob(JEDClientLightTracker tracker,JEDClientLightTracker.Chunk chunk){
        this.tracker=tracker;this.chunk=chunk;sections=new Work[tracker.view.sectionCount()];
        Arrays.setAll(sections,i->new Work());
        sectionCursor=Math.clamp(tracker.view.playerSection()-tracker.view.minSection(),0,sections.length-1);
    }
    void invalidate(int index){
        for(int i=Math.max(0,index-1);i<=Math.min(sections.length-1,index+1);i++){
            sections[i]=new Work();sections[i].priority=true;
            if(pendingSection==i){pendingIndex=pendingCount=0;pendingSection=-1;}
        }
        waitingEpoch=Long.MIN_VALUE;
    }
    void borderChanged(int index,int dx,int dz){
        for(int i=Math.max(0,index-1);i<=Math.min(sections.length-1,index+1);i++){
            var w=sections[i];
            // The changed chunk is in the opposite direction from this neighbor.
            for(int p=0;p<4096;p++){
                int x=p&15,z=(p>>>4)&15;
                if(dx>0&&x==0||dx<0&&x==15||dz>0&&z==0||dz<0&&z==15)w.deferred.set(p);
            }
            w.retriedEpoch=Long.MIN_VALUE;
        }
        waitingEpoch=Long.MIN_VALUE;
    }
    public boolean done(){if(pendingIndex<pendingCount)return false;for(var s:sections)if(!s.done())return false;return true;}
    boolean mayRun(){return waitingEpoch!=tracker.epoch;}
    int remainingSections(){int n=0;for(var w:sections)if(!w.done())n++;return n;}
    int waitingBorders(){int n=0;for(var w:sections)n+=w.deferred.cardinality();return n;}
    void step(JEDClientLightScheduler budget,int slice,long deadline){
        if(!mayRun())return;
        int visited=0,attempted=0;
        while((System.nanoTime()-deadline<0||budget.minimumWorkPending())&&visited<slice){
            if(pendingIndex<pendingCount){
                int sy=tracker.view.minSection()+pendingSection;
                if(!tracker.readyAt(chunk,sy,position.getX(),position.getZ()))return;
                if(!budget.submit(pending[pendingIndex]))return;
                pendingIndex++;continue;
            }
            if(!budget.canExamine())return;
            int index=choose();
            if(index<0){waitingEpoch=tracker.epoch;return;}
            if(++attempted>sections.length+slice*2){waitingEpoch=tracker.epoch;return;}
            var w=sections[index];int sy=tracker.view.minSection()+index;
            if(!w.classified){
                var nativeChunk=tracker.view.chunk(chunk.x,chunk.z);
                var pos=SectionPos.of(chunk.x,sy,chunk.z);
                w.full=!nativeChunk.getSection(index).hasOnlyAir()
                    ||tracker.view.engine().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos)!=null
                    ||tracker.view.engine().getLayerListener(LightLayer.SKY).getDataLayerData(pos)!=null;
                w.classified=true;
            }
            int p;
            if(w.cursor<4096){
                p=w.cursor++;
                if(!w.full){
                    while(p<4096&&!boundary(p))p=w.cursor++;
                    if(p>=4096){w.cursor=4096;continue;}
                }
            }else{
                if(w.retriedEpoch!=tracker.epoch){w.retryCursor=0;w.retriedEpoch=tracker.epoch;}
                p=w.deferred.nextSetBit(w.retryCursor);
                if(p<0)continue;
                w.retryCursor=p+1;
            }
            int x=(chunk.x<<4)+(p&15),y=(sy<<4)+(p>>>8),z=(chunk.z<<4)+((p>>>4)&15);
            budget.examined();visited++;chunk.visited++;
            if(!tracker.readyAt(chunk,sy,x,z)){w.deferred.set(p);continue;}
            w.deferred.clear(p);
            position.set(x,y,z);pendingIndex=pendingCount=0;pendingSection=index;
            var state=tracker.view.geometry().getBlockState(position);
            if(state.isAir()){
                pending[pendingCount++]=position.asLong();
                for(int d=0;d<6;d++){
                    int ny=y+DY[d];if(ny<tracker.view.minY()||ny>=tracker.view.maxY())continue;
                    adjacent.set(x+DX[d],ny,z+DZ[d]);
                    var neighbor=tracker.view.geometry().getBlockState(adjacent);
                    if(neighbor.getLightEmission(tracker.view.geometry(),adjacent)>0)pending[pendingCount++]=adjacent.asLong();
                }
            }
        }
    }
    private int choose(){
        // Alternate priority service with an unconditional round-robin share. This ages all ready
        // sections even under sustained nearby imports, while keeping visible work responsive.
        int chosen=-1,bestDistance=Integer.MAX_VALUE;boolean hasDirty=false;
        for(int n=0;n<sections.length;n++){
            int i=(sectionCursor+n)%sections.length;var w=sections[i];
            if(w.done()||!tracker.ready(chunk,tracker.view.minSection()+i))continue;
            if(w.cursor>=4096&&w.retriedEpoch==tracker.epoch&&w.deferred.nextSetBit(w.retryCursor)<0)continue;
            if(!preferPriority){chosen=i;break;}
            int distance=Math.abs(tracker.view.minSection()+i-tracker.view.playerSection());
            if(chosen<0||w.priority&&!hasDirty||w.priority==hasDirty&&distance<bestDistance){
                chosen=i;bestDistance=distance;hasDirty=w.priority;
            }
        }
        if(chosen>=0){sectionCursor=(chosen+1)%sections.length;preferPriority=!preferPriority;}
        return chosen;
    }
    private static boolean boundary(int p){int x=p&15,z=(p>>>4)&15,y=p>>>8;return x==0||x==15||y==0||y==15||z==0||z==15;}
}
