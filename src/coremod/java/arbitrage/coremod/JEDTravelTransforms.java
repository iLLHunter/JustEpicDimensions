package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Only covers native tick paths that bypass EntityTickEvent.Pre and movement during an automatic hold. */
final class JEDTravelTransforms extends ArbitrageCoremod.Transformer {
    private static final String BRIDGE="justepicdimensions/travel/JEDTeleportService";
    private static final String ENTITY="Lnet/minecraft/world/entity/Entity;",CONNECTION="Lnet/minecraft/server/network/ServerGamePacketListenerImpl;";
    private final String name;
    JEDTravelTransforms(String name){this.name=name;}
    static List<JEDTravelTransforms> all(){return List.of(new JEDTravelTransforms("net.minecraft.server.level.ServerLevel"),new JEDTravelTransforms("net.minecraft.server.level.ServerPlayer"),new JEDTravelTransforms("net.minecraft.server.network.ServerGamePacketListenerImpl"));}
    String target(){return name;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){
        int passengers=0,players=0,movement=0,floating=0;
        for(var method:type.methods){
            if(name.endsWith("ServerPlayer")&&method.name.equals("doTick")&&method.desc.equals("()V")){
                var continueTick=new LabelNode();var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"holdNative","("+ENTITY+")Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,continueTick));code.add(new InsnNode(Opcodes.RETURN));code.add(continueTick);method.instructions.insert(code);players++;
            }
            for(var node:method.instructions.toArray()){
                if(name.endsWith("ServerLevel")&&method.name.equals("tickPassenger")&&node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/entity/Entity")&&call.name.equals("rideTick")&&call.desc.equals("()V")){
                    // Preserve the native invocation for other transformers' call-site hooks
                    // (including ServerCore's passenger activation condition).
                    var tick=new LabelNode();var done=new LabelNode();var code=new InsnList();
                    code.add(new InsnNode(Opcodes.DUP));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"holdNative","("+ENTITY+")Z",false));
                    code.add(new JumpInsnNode(Opcodes.IFEQ,tick));code.add(new InsnNode(Opcodes.POP));code.add(new JumpInsnNode(Opcodes.GOTO,done));code.add(tick);
                    method.instructions.insertBefore(call,code);method.instructions.insert(call,done);method.maxStack++;passengers++;
                }
                if(name.endsWith("ServerGamePacketListenerImpl")&&(method.name.equals("handleMovePlayer")||method.name.equals("handleMoveVehicle"))&&node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/protocol/PacketUtils")&&call.name.equals("ensureRunningOnSameThread")){
                    var continueMove=new LabelNode();var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"player","Lnet/minecraft/server/level/ServerPlayer;"));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"holdNative","("+ENTITY+")Z",false));code.add(new JumpInsnNode(Opcodes.IFEQ,continueMove));code.add(new InsnNode(Opcodes.RETURN));code.add(continueMove);method.instructions.insert(call,code);movement++;
                }
                if(name.endsWith("ServerGamePacketListenerImpl")&&method.name.equals("tick")&&node instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETFIELD&&field.owner.equals(type.name)&&(field.name.equals("clientIsFloating")||field.name.equals("clientVehicleIsFloating"))){
                    var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"floating","(Z"+CONNECTION+")Z",false));method.instructions.insert(field,code);floating++;
                }
            }
        }
        require(name.endsWith("ServerLevel")?passengers==1:name.endsWith("ServerPlayer")?players==1:movement==2&&floating==2,target());
        System.setProperty("jed.travel.coremod."+name,"verified");return type;
    }
}
