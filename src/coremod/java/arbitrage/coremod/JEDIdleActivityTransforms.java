package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Record native player/ticket activity when it happens, including activity between bounded idle scans. */
final class JEDIdleActivityTransforms extends ArbitrageCoremod.Transformer {
    private static final String BRIDGE="justepicdimensions/dimension/JEDDimensionLifecycle";
    String target(){return "net.minecraft.server.level.DistanceManager";}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){
        int tickets=0,players=0;
        for(var method:type.methods){
            if((method.name.equals("addTicket")||method.name.equals("removeTicket"))&&method.desc.equals("(JLnet/minecraft/server/level/Ticket;)V")){
                var head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new VarInsnNode(Opcodes.LLOAD,1));head.add(new VarInsnNode(Opcodes.ALOAD,3));
                head.add(new InsnNode(method.name.equals("addTicket")?Opcodes.ICONST_1:Opcodes.ICONST_0));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"ticketActivity","(Lnet/minecraft/server/level/DistanceManager;JLnet/minecraft/server/level/Ticket;Z)V",false));
                method.instructions.insert(head);method.maxStack=Math.max(method.maxStack,5);tickets++;
            }
            if((method.name.equals("addPlayer")||method.name.equals("removePlayer"))&&method.desc.equals("(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V")){
                var head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"playerActivity","(Lnet/minecraft/server/level/DistanceManager;)V",false));
                method.instructions.insert(head);players++;
            }
        }
        require(tickets==2&&players==2,"idle player/ticket activity anchors");
        System.setProperty("jed.idle.activity.coremod","verified");return type;
    }
}
