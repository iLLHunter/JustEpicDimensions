package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Preserve imported primary settings after dedicated/integrated native startup defaults. */
final class JEDTemplateTransforms implements ITransformer<ClassNode> {
    public Set<Target<ClassNode>> targets(){return Set.of(Target.targetClass("net.minecraft.server.MinecraftServer"));}
    public TargetType<ClassNode> getTargetType(){return TargetType.CLASS;}
    public TransformerVoteResult castVote(ITransformerVotingContext context){return TransformerVoteResult.YES;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int anchors=0;for(var method:type.methods)if(method.name.equals("loadLevel")&&method.desc.equals("()V"))for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.name.equals("forceDifficulty")&&call.desc.equals("()V")){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/structure/JEDTemplateManager","restorePrimary","(Lnet/minecraft/server/MinecraftServer;)V",false));method.instructions.insert(call,out);anchors++;}if(anchors!=1)throw new IllegalStateException("JED template startup anchor mismatch: "+anchors);System.setProperty("jed.template.coremod.defaults","verified");return type;}
}
