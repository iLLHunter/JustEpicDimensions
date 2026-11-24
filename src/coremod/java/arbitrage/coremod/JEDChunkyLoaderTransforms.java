package arbitrage.coremod;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
/** Track the actual last native checkpoint writer, including external tasks which finish between polls. */
final class JEDChunkyLoaderTransforms extends ArbitrageCoremod.Transformer {
 String target(){return "org.popcraft.chunky.util.TaskLoader";}
 public ClassNode transform(ClassNode node,ITransformerVotingContext context){var save=node.methods.stream().filter(m->m.name.equals("saveTask")&&m.desc.equals("(Lorg/popcraft/chunky/GenerationTask;)V")).findFirst().orElse(null);if(save==null)return node;var returns=new java.util.ArrayList<AbstractInsnNode>();for(var i:save.instructions.toArray())if(i.getOpcode()==Opcodes.RETURN)returns.add(i);if(returns.size()!=1)return node;var hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/pregen/JEDPregenerationHooks","saved","(Ljava/lang/Object;)V",false));save.instructions.insertBefore(returns.getFirst(),hook);save.maxStack=Math.max(save.maxStack,1);node.interfaces.add("justepicdimensions/pregen/JEDChunkyLoaderAccess");return node;}
}
