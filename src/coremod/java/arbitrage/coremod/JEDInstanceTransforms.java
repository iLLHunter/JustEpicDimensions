package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Save native team identity with the scoreboard; protect all queued gameplay packets after thread dispatch. */
final class JEDInstanceTransforms extends ArbitrageCoremod.Transformer {
    private final String name;
    JEDInstanceTransforms(String name){this.name=name;}
    static List<JEDInstanceTransforms> all(){return List.of(new JEDInstanceTransforms("net.minecraft.world.scores.ScoreboardSaveData"),new JEDInstanceTransforms("net.minecraft.server.network.ServerGamePacketListenerImpl"));}
    String target(){return name;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int loads=0,saves=0,packets=0;
        for(var method:type.methods){
            if(name.endsWith("ScoreboardSaveData")){
                if(method.name.equals("loadTeams"))for(var node:method.instructions.toArray())if(node.getOpcode()==Opcodes.RETURN){var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"scoreboard","Lnet/minecraft/world/scores/Scoreboard;"));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/instance/JEDInstanceTeams","loaded","(Lnet/minecraft/nbt/ListTag;Lnet/minecraft/world/scores/Scoreboard;)V",false));method.instructions.insertBefore(node,code);method.maxStack+=2;loads++;}
                if(method.name.equals("saveTeams"))for(var node:method.instructions.toArray())if(node.getOpcode()==Opcodes.ARETURN){var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"scoreboard","Lnet/minecraft/world/scores/Scoreboard;"));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/instance/JEDInstanceTeams","saved","(Lnet/minecraft/nbt/ListTag;Lnet/minecraft/world/scores/Scoreboard;)Lnet/minecraft/nbt/ListTag;",false));method.instructions.insertBefore(node,code);method.maxStack++;saves++;}
            }else if(method.name.startsWith("handle")&&method.desc.endsWith(")V"))for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/protocol/PacketUtils")&&call.name.equals("ensureRunningOnSameThread")){var next=new LabelNode();var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"player","Lnet/minecraft/server/level/ServerPlayer;"));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/instance/JEDInstanceController","guard","(Lnet/minecraft/server/level/ServerPlayer;)Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,next));code.add(new InsnNode(Opcodes.RETURN));code.add(next);method.instructions.insert(call,code);packets++;}
        }
        require(name.endsWith("ScoreboardSaveData")?loads==1&&saves==1:packets>=20,"instance ownership/packet anchors: "+name+" "+loads+"/"+saves+"/"+packets);System.setProperty("jed.instances.coremod."+name,"verified");return type;
    }
}
