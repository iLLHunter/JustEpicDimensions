package arbitrage.coremod;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
final class JEDChunkySchedulerTransforms extends ArbitrageCoremod.Transformer {
 String target(){return "org.popcraft.chunky.util.TaskScheduler";}
 public ClassNode transform(ClassNode node,ITransformerVotingContext context){var run=node.methods.stream().filter(m->m.name.equals("runTask")&&m.desc.equals("(Ljava/lang/Runnable;)V")).findFirst().orElse(null);if(run==null)return node;var calls=new java.util.ArrayList<MethodInsnNode>();for(var i:run.instructions.toArray())if(i instanceof MethodInsnNode m&&m.owner.equals("java/util/concurrent/ExecutorService")&&m.name.equals("submit")&&m.desc.equals("(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))calls.add(m);if(calls.size()!=1)return node;var old=calls.getFirst();run.instructions.set(old,new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/pregen/JEDPregenerationHooks","submit","(Ljava/util/concurrent/ExecutorService;Ljava/lang/Runnable;)Ljava/util/concurrent/Future;",false));node.interfaces.add("justepicdimensions/pregen/JEDChunkySchedulerAccess");return node;}
}
