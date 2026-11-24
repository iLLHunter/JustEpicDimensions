package justepicdimensions.pregen;

import org.popcraft.chunky.*;
import org.popcraft.chunky.platform.NeoForgeWorld;
import org.popcraft.chunky.util.Parameter;
import net.minecraft.server.level.ServerLevel;
import justepicdimensions.definition.JEDDefinitionException;

/** Public Chunky API 0 plus its public ownership/progress API, verified against NeoForge 1.4.23. */
final class JEDChunkyAdapter implements JEDChunkyBackend {
 private final Chunky chunky;
 private record Handle(GenerationTask task,NeoForgeWorld world,long failures){}
 JEDChunkyAdapter(){chunky=ChunkyProvider.get();if(chunky.getApi().version()!=0||!JEDChunkyWorldAccess.class.isAssignableFrom(NeoForgeWorld.class)||!JEDChunkyTaskAccess.class.isAssignableFrom(GenerationTask.class)||!JEDChunkySchedulerAccess.class.isAssignableFrom(org.popcraft.chunky.util.TaskScheduler.class)||!JEDChunkyLoaderAccess.class.isAssignableFrom(org.popcraft.chunky.util.TaskLoader.class))throw new JEDDefinitionException("Unsupported Chunky API or missing matching Arbitrage generation safety hooks");}
 private NeoForgeWorld world(ServerLevel level){return new NeoForgeWorld(level);}
 private GenerationTask active(NeoForgeWorld world){return chunky.getGenerationTasks().get(world.getName());}
 private GenerationTask saved(NeoForgeWorld world){var task=chunky.getTaskLoader().loadTask(world).orElse(null);return task!=null&&!task.isCancelled()?task:null;}
 private static String receipt(GenerationTask task){var s=task.getSelection();var plan=new JEDPregenerationPolicy.Plan(s.shape(),s.centerX(),s.centerZ(),s.radiusX(),s.radiusZ(),s.pattern().getType());var json=plan.json();json.addProperty("chunks",task.getCount());json.addProperty("milliseconds",task.getTotalTime());return justepicdimensions.bundle.JEDDimensionBundle.hash(justepicdimensions.bundle.JEDDimensionBundle.json(json));}
 private static boolean same(GenerationTask task,JEDPregenerationPolicy.Plan plan){var s=task.getSelection();return s.shape().equals(plan.shape())&&s.centerX()==plan.x()&&s.centerZ()==plan.z()&&s.radiusX()==plan.radiusX()&&s.radiusZ()==plan.radiusZ()&&s.pattern().getType().equals(plan.pattern());}
 public Object launch(ServerLevel level,JEDPregenerationPolicy.Plan plan,String expected){
  var world=world(level);if(active(world)!=null)throw new JEDDefinitionException("Another Chunky task is active for "+world.getName());
  var saved=saved(world);GenerationTask task;
  if(saved!=null){if(expected==null||!receipt(saved).equals(expected)||!same(saved,plan))throw new JEDDefinitionException("Saved Chunky work is not proven to belong to this JED job; it was left untouched");task=saved;}
  else task=new GenerationTask(chunky,Selection.builder(chunky,world).shape(plan.shape()).center(plan.x(),plan.z()).radiusX(plan.radiusX()).radiusZ(plan.radiusZ()).pattern(Parameter.of(plan.pattern())).build());
  if(chunky.getGenerationTasks().putIfAbsent(world.getName(),task)!=null)throw new JEDDefinitionException("Another Chunky task claimed this dimension");
  var handle=new Handle(task,world,JEDPregenerationHooks.failures(level));try{chunky.getScheduler().runTask(task);}catch(RuntimeException error){chunky.getGenerationTasks().remove(world.getName(),task);throw error;}return handle;
 }
 public void pause(Object raw){var h=(Handle)raw;if(active(h.world())==h.task()){h.task().stop(false);if(JEDPregenerationHooks.crashed(h.task())&&JEDPregenerationHooks.tasks(h.world().getWorld())==0)chunky.getGenerationTasks().remove(h.world().getName(),h.task());}}
 public Progress progress(Object raw){var h=(Handle)raw;var current=active(h.world());long count=h.task().getCount();var p=h.task().getProgress();return new Progress(count,p.getPercentComplete(),p.isComplete(),current==h.task(),current!=null&&current!=h.task(),JEDPregenerationHooks.failures(h.world().getWorld())>h.failures()||JEDPregenerationHooks.crashed(h.task()));}
 public String saveReceipt(Object raw){var h=(Handle)raw;if(active(h.world())!=null||JEDPregenerationHooks.retained(h.world().getWorld()))throw new JEDDefinitionException("Chunky has not drained");if(!JEDPregenerationHooks.savedBy(h.world().getWorld(),h.task()))throw new JEDDefinitionException("Native saved progress has another writer; foreign work was left untouched");chunky.getTaskLoader().saveTask(h.task());var saved=saved(h.world());return saved==null?null:receipt(saved);}
 public boolean cancelSaved(ServerLevel level,String expected){var world=world(level);if(active(world)!=null)return false;var saved=saved(world);if(saved==null)return true;if(expected==null||!receipt(saved).equals(expected))return false;chunky.getTaskLoader().cancelTask(world);return true;}
 public void reset(net.minecraft.resources.ResourceLocation id){chunky.getRegionCache().clear(id.toString());}
 public boolean foreign(ServerLevel level,Object raw){var active=active(world(level));return active!=null&&(raw==null||active!=((Handle)raw).task());}
}
