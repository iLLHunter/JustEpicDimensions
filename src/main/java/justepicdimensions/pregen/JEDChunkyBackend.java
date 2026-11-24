package justepicdimensions.pregen;

import net.minecraft.server.level.ServerLevel;

/** Optional classes stay behind this boundary; a server without Chunky never links them. */
interface JEDChunkyBackend {
 record Progress(long chunks,double percent,boolean complete,boolean running,boolean replaced,boolean failed){}
 Object launch(ServerLevel level,JEDPregenerationPolicy.Plan plan,String receipt);
 void pause(Object handle);
 Progress progress(Object handle);
 String saveReceipt(Object handle);
 boolean cancelSaved(ServerLevel level,String receipt);
 boolean foreign(ServerLevel level,Object own);
 void reset(net.minecraft.resources.ResourceLocation id);
 static JEDChunkyBackend create(){return new JEDChunkyAdapter();}
}
