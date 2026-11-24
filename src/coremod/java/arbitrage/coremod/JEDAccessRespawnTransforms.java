package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Retain the native respawn tail once, while admission may await a redirected destination's preparation. */
final class JEDAccessRespawnTransforms extends ArbitrageCoremod.Transformer {
    private static final String P="Lnet/minecraft/server/level/ServerPlayer;",R="Lnet/minecraft/world/entity/Entity$RemovalReason;",T="Lnet/minecraft/world/level/portal/DimensionTransition;",E="Lnet/neoforged/neoforge/event/entity/player/PlayerRespawnPositionEvent;";
    String target(){return "net.minecraft.server.players.PlayerList";}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){
        MethodNode original=null;AbstractInsnNode split=null;int anchors=0;
        for(var method:type.methods)if(method.name.equals("respawn")&&method.desc.equals("("+P+"Z"+R+")"+P)){
            original=method;
            for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.name.equals("getDimensionTransition")&&call.owner.endsWith("/PlayerRespawnPositionEvent")){
                var next=call.getNext();while(next instanceof LabelNode||next instanceof LineNumberNode||next instanceof FrameNode)next=next.getNext();
                require(next instanceof VarInsnNode store&&store.getOpcode()==Opcodes.ASTORE&&store.var==4,"final respawn transition local");split=next.getNext();anchors++;
            }
        }
        require(anchors==1&&original!=null&&split!=null,"one final native respawn destination");
        require(original.tryCatchBlocks.isEmpty(),"native respawn continuation has no cross-boundary exception handlers");
        var labels=new HashMap<LabelNode,LabelNode>();for(var n:original.instructions.toArray())if(n instanceof LabelNode label)labels.put(label,new LabelNode());
        var tail=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,"jed$resumeRespawn","("+P+"Z"+R+T+E+")"+P,null,null);
        for(var n=split;n!=null;n=n.getNext())if(!(n instanceof FrameNode)&&!(n instanceof LineNumberNode))tail.instructions.add(n.clone(labels));
        tail.maxLocals=original.maxLocals;tail.maxStack=Math.max(original.maxStack,6);type.methods.add(tail);type.interfaces.add("justepicdimensions/access/JEDAccessRespawnAccess");
        for(var n=split;n!=null;){var next=n.getNext();original.instructions.remove(n);n=next;}
        if(original.localVariables!=null)original.localVariables.clear();
        for(var n:original.instructions.toArray())if(n instanceof FrameNode)original.instructions.remove(n);
        for(int i:new int[]{0,1})original.instructions.add(new VarInsnNode(Opcodes.ALOAD,i));
        original.instructions.add(new VarInsnNode(Opcodes.ILOAD,2));original.instructions.add(new VarInsnNode(Opcodes.ALOAD,3));original.instructions.add(new VarInsnNode(Opcodes.ALOAD,4));original.instructions.add(new VarInsnNode(Opcodes.ALOAD,5));
        original.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/access/JEDAccessController","respawn","(Lnet/minecraft/server/players/PlayerList;"+P+"Z"+R+T+E+")"+P,false));
        original.instructions.add(new InsnNode(Opcodes.ARETURN));original.maxStack=Math.max(original.maxStack,6);
        System.setProperty("jed.access.respawn.coremod","verified");return type;
    }
}
