package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Optional platform hook: never alter a mismatching backend, and never mark unprotected worlds supported. */
final class JEDChunkyTransforms extends ArbitrageCoremod.Transformer {
    String target(){return "org.popcraft.chunky.platform.NeoForgeWorld";}
    public ClassNode transform(ClassNode node,ITransformerVotingContext context){
        int methods=0;for(var method:node.methods)if(selected(method)){int returns=0;for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN)returns++;if(returns==0)return node;methods++;}
        if(methods!=2||node.methods.stream().noneMatch(m->m.name.equals("getWorld")&&m.desc.equals("()Lnet/minecraft/server/level/ServerLevel;")))return node;
        for(var method:node.methods)if(selected(method))for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN){
            var hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,node.name,"getWorld","()Lnet/minecraft/server/level/ServerLevel;",false));hook.add(new InsnNode(Opcodes.SWAP));hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/pregen/JEDPregenerationHooks","track","(Ljava/lang/Object;Ljava/util/concurrent/CompletableFuture;)Ljava/util/concurrent/CompletableFuture;",false));method.instructions.insertBefore(instruction,hook);method.maxStack=Math.max(method.maxStack,3);
        }
        var bridge=new MethodNode(Opcodes.ACC_PUBLIC,"jed$pregenLevel","()Ljava/lang/Object;",null,null);bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,node.name,"getWorld","()Lnet/minecraft/server/level/ServerLevel;",false));bridge.instructions.add(new InsnNode(Opcodes.ARETURN));bridge.maxStack=1;bridge.maxLocals=1;node.methods.add(bridge);
        if(!node.interfaces.contains("justepicdimensions/pregen/JEDChunkyWorldAccess"))node.interfaces.add("justepicdimensions/pregen/JEDChunkyWorldAccess");return node;
    }
    private static boolean selected(MethodNode method){return (method.name.equals("isChunkGenerated")||method.name.equals("getChunkAtAsync"))&&method.desc.equals("(II)Ljava/util/concurrent/CompletableFuture;");}
}
