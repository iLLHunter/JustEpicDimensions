package arbitrage.coremod;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
final class JEDChunkyTaskTransforms extends ArbitrageCoremod.Transformer {
 String target(){return "org.popcraft.chunky.GenerationTask";}
 public ClassNode transform(ClassNode node,ITransformerVotingContext context){
  var run=node.methods.stream().filter(m->m.name.equals("run")&&m.desc.equals("()V")).findFirst().orElse(null);if(run==null)return node;
  var calls=new java.util.ArrayList<MethodInsnNode>();for(var i:run.instructions.toArray())if(i instanceof MethodInsnNode m&&m.owner.equals("java/util/concurrent/CompletableFuture")&&m.name.equals("whenComplete")&&m.desc.equals("(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;"))calls.add(m);if(calls.size()!=1)return node;
  var hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,node.name,"getSelection","()Lorg/popcraft/chunky/Selection;",false));hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"org/popcraft/chunky/Selection","world","()Lorg/popcraft/chunky/platform/World;",false));hook.add(new InsnNode(Opcodes.SWAP));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/pregen/JEDPregenerationHooks","trackWorld","(Ljava/lang/Object;Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",false));run.instructions.insert(calls.getFirst(),hook);run.maxStack=Math.max(run.maxStack,4);var bridge=new MethodNode(Opcodes.ACC_PUBLIC,"jed$pregenWorld","()Ljava/lang/Object;",null,null);bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,node.name,"getSelection","()Lorg/popcraft/chunky/Selection;",false));bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"org/popcraft/chunky/Selection","world","()Lorg/popcraft/chunky/platform/World;",false));bridge.instructions.add(new InsnNode(Opcodes.ARETURN));bridge.maxStack=1;bridge.maxLocals=1;node.methods.add(bridge);node.interfaces.add("justepicdimensions/pregen/JEDChunkyTaskAccess");return node;
 }
}
