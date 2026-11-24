package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Capture before native travel/equipment changes and serialize category progress with native player data. */
final class JEDPlayerCategoryTransforms extends ArbitrageCoremod.Transformer {
    private static final String BRIDGE="justepicdimensions/player/JEDPlayerGroupController";
    private static final String PLAYER="Lnet/minecraft/server/level/ServerPlayer;";
    private final String name;
    private JEDPlayerCategoryTransforms(String name){this.name=name;}
    static java.util.List<JEDPlayerCategoryTransforms> all(){return java.util.List.of(new JEDPlayerCategoryTransforms("net.minecraft.server.level.ServerPlayer"),new JEDPlayerCategoryTransforms("net.minecraft.world.entity.Entity"));}
    String target(){return name;}
    private static InsnList invoke(String method,String descriptor,int... locals){
        var list=new InsnList();for(int local:locals)list.add(new VarInsnNode(Opcodes.ALOAD,local));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,method,descriptor,false));return list;
    }
    public ClassNode transform(ClassNode node,ITransformerVotingContext context){
        if(name.endsWith(".Entity")){
            int saves=0;for(var method:node.methods)if(method.name.equals("saveWithoutId")&&method.desc.equals("(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;")){
                // Entity copies NeoForgeData before calling ServerPlayer.addAdditionalSaveData.
                method.instructions.insert(invoke("saveEntity","(Lnet/minecraft/world/entity/Entity;)V",0));method.maxStack=Math.max(1,method.maxStack);saves++;
            }
            require(saves==1,"category save before native persistent-data copy");System.setProperty("jed.player.categories.save.coremod","verified");return node;
        }
        int saves=0,loads=0,clones=0,travels=0,cloneHealth=0;
        for(var method:node.methods){
            if(method.name.equals("addAdditionalSaveData")&&method.desc.equals("(Lnet/minecraft/nbt/CompoundTag;)V")){
                saves++;
            }
            if(method.name.equals("readAdditionalSaveData")&&method.desc.equals("(Lnet/minecraft/nbt/CompoundTag;)V")){
                for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){method.instructions.insertBefore(instruction,invoke("loaded","("+PLAYER+"Lnet/minecraft/nbt/CompoundTag;)V",0,1));loads++;}
            }
            if(method.name.equals("restoreFrom")&&method.desc.equals("("+PLAYER+"Z)V")){
                method.instructions.insert(invoke("cloned","("+PLAYER+PLAYER+")V",0,1));clones++;
                for(var instruction:method.instructions.toArray())if(instruction instanceof MethodInsnNode call&&call.name.equals("setHealth")&&call.desc.equals("(F)V")){
                    var after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,0));after.add(new VarInsnNode(Opcodes.ALOAD,1));after.add(new VarInsnNode(Opcodes.ILOAD,2));after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,"clonedHealth","("+PLAYER+PLAYER+"Z)V",false));method.instructions.insert(instruction,after);cloneHealth++;
                }
            }
            if(method.name.equals("changeDimension")&&method.desc.equals("(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;")){
                var start=new LabelNode();var end=new LabelNode();var failure=new LabelNode();
                var head=invoke("beforeTravel","("+PLAYER+"Lnet/minecraft/world/level/portal/DimensionTransition;)V",0,1);head.add(start);method.instructions.insert(head);
                int returns=0;for(var instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.ARETURN){
                    method.instructions.insertBefore(instruction,invoke("afterTravel","("+PLAYER+")V",0));returns++;
                }
                require(returns>=4,"category native travel returns");
                method.instructions.add(end);method.instructions.add(failure);method.instructions.add(invoke("afterTravel","("+PLAYER+")V",0));method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,failure,null));travels++;
            }
            method.maxStack=Math.max(method.maxStack,3);
        }
        require(saves==1&&loads==1&&clones==1&&travels==1&&cloneHealth==2,"category native save/load/clone/travel anchors");
        System.setProperty("jed.player.categories.coremod","verified");return node;
    }
}
