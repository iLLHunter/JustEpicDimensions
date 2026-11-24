package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Keep native tick queues intact while an owned structure spans multiple server ticks. */
final class JEDStructureTransforms extends ArbitrageCoremod.Transformer {
    private final String name;
    JEDStructureTransforms(String name){this.name=name;}
    static List<JEDStructureTransforms> all(){return List.of(new JEDStructureTransforms("net.minecraft.server.level.ServerLevel"),new JEDStructureTransforms("net.minecraft.world.level.chunk.LevelChunk"));}
    String target(){return name;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int anchors=0;
        for(var method:type.methods){
            if(name.endsWith("ServerLevel")&&method.name.equals("shouldTickBlocksAt")&&method.desc.equals("(J)Z")){var args=new InsnList();args.add(new VarInsnNode(Opcodes.ALOAD,0));args.add(new VarInsnNode(Opcodes.LLOAD,1));guard(method,args,"(Lnet/minecraft/server/level/ServerLevel;J)Z",true);anchors++;}
            if(name.endsWith("ServerLevel")&&method.name.equals("tickChunk")&&method.desc.equals("(Lnet/minecraft/world/level/chunk/LevelChunk;I)V")){var args=new InsnList();args.add(new VarInsnNode(Opcodes.ALOAD,1));guard(method,args,"(Lnet/minecraft/world/level/chunk/LevelChunk;)Z",false);anchors++;}
            if(name.endsWith("LevelChunk")&&method.name.equals("isTicking")&&method.desc.equals("(Lnet/minecraft/core/BlockPos;)Z")){var args=new InsnList();args.add(new VarInsnNode(Opcodes.ALOAD,0));guard(method,args,"(Lnet/minecraft/world/level/chunk/LevelChunk;)Z",true);anchors++;}
        }
        int expected=name.endsWith("ServerLevel")?2:1;if(anchors!=expected)throw new IllegalStateException("JED structure tick anchor mismatch for "+name+": "+anchors);System.setProperty("jed.structure.coremod."+name,"verified");return type;
    }
    private static void guard(MethodNode method,InsnList args,String descriptor,boolean returnsBoolean){var end=new LabelNode();args.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/structure/JEDStructurePlacer","holdChunk",descriptor,false));args.add(new JumpInsnNode(Opcodes.IFEQ,end));if(returnsBoolean)args.add(new InsnNode(Opcodes.ICONST_0));args.add(new InsnNode(returnsBoolean?Opcodes.IRETURN:Opcodes.RETURN));args.add(end);method.instructions.insert(args);method.maxStack=Math.max(method.maxStack,5);}
}
