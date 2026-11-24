package arbitrage.coremod;

import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Exact native boundaries for contribution ownership and independent direct-damage timing. */
final class JEDEnvironmentTransforms extends ArbitrageCoremod.Transformer {
    private static final String EFFECTS="justepicdimensions/environment/JEDEnvironmentEffects", CONTROL="justepicdimensions/environment/JEDEnvironmentController";
    private static final String LIVING="Lnet/minecraft/world/entity/LivingEntity;", EFFECT="Lnet/minecraft/world/effect/MobEffectInstance;", SOURCE="Lnet/minecraft/world/damagesource/DamageSource;";
    private final String name;
    private JEDEnvironmentTransforms(String name){this.name=name;}
    static java.util.List<JEDEnvironmentTransforms> all(){return java.util.List.of(new JEDEnvironmentTransforms("net.minecraft.world.entity.LivingEntity"),new JEDEnvironmentTransforms("net.minecraft.world.effect.MobEffectInstance"));}
    String target(){return name;}
    private static InsnList invoke(String owner,String method,String descriptor,int... locals){var out=new InsnList();for(int local:locals)out.add(new VarInsnNode(Opcodes.ALOAD,local));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,method,descriptor,false));return out;}
    public ClassNode transform(ClassNode node,ITransformerVotingContext context){
        int added=0,removed=0,detached=0,forced=0,ticked=0,cooldown=0,writes=0;
        for(var method:node.methods){
            if(name.endsWith(".MobEffectInstance")&&method.name.equals("tick")&&method.desc.equals("("+LIVING+"Ljava/lang/Runnable;)Z")){
                for(var n:method.instructions.toArray())if(n.getOpcode()==Opcodes.IRETURN){method.instructions.insertBefore(n,invoke(EFFECTS,"tick","("+EFFECT+LIVING+")V",0,1));ticked++;}
            }
            if(name.endsWith(".LivingEntity")){
                if(method.name.equals("addEffect")&&method.desc.equals("("+EFFECT+"Lnet/minecraft/world/entity/Entity;)Z"))for(var n:method.instructions.toArray())if(n instanceof MethodInsnNode call&&call.name.equals("post")&&call.owner.equals("net/neoforged/bus/api/IEventBus")){method.instructions.insert(n,invoke(EFFECTS,"accepted","("+LIVING+EFFECT+")V",0,1));added++;}
                if(method.name.equals("removeEffectNoUpdate")&&method.desc.equals("(Lnet/minecraft/core/Holder;)"+EFFECT))for(var n:method.instructions.toArray())if(n.getOpcode()==Opcodes.ARETURN){var out=new InsnList();out.add(new InsnNode(Opcodes.DUP));out.add(invoke(EFFECTS,"removedWithoutUpdate","("+EFFECT+LIVING+")V",0));method.instructions.insertBefore(n,out);detached++;}
                if(method.name.equals("onEffectRemoved")&&method.desc.equals("("+EFFECT+")V")){method.instructions.insert(invoke(EFFECTS,"removed","("+LIVING+EFFECT+")V",0,1));removed++;}
                if(method.name.equals("forceAddEffect")&&method.desc.equals("("+EFFECT+"Lnet/minecraft/world/entity/Entity;)V"))for(var n:method.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN){method.instructions.insertBefore(n,invoke(EFFECTS,"forced","("+LIVING+EFFECT+")V",0,1));forced++;}
                if(method.name.equals("hurt")&&method.desc.equals("("+SOURCE+"F)Z"))for(var n:method.instructions.toArray()){
                    if(n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.name.equals("BYPASSES_COOLDOWN")){
                        var call=ArbitrageCoremod.Storage.real(n.getNext());require(call instanceof MethodInsnNode c&&c.name.equals("is"),"environment native cooldown tag");method.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,CONTROL,"cooldown","("+SOURCE+"Lnet/minecraft/tags/TagKey;)Z",false));cooldown++;
                    }
                    if(n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&java.util.Set.of("lastHurt","invulnerableTime","hurtDuration","hurtTime").contains(field.name)){
                        require(field.desc.equals("F")||field.desc.equals("I"),"environment timer slot width");var write=new LabelNode();var done=new LabelNode();var out=invoke(CONTROL,"independent","("+SOURCE+")Z",1);out.add(new JumpInsnNode(Opcodes.IFEQ,write));out.add(new InsnNode(Opcodes.POP2));out.add(new JumpInsnNode(Opcodes.GOTO,done));out.add(write);method.instructions.insertBefore(n,out);method.instructions.insert(n,done);writes++;
                    }
                }
            }
            method.maxStack=Math.max(method.maxStack,5);
        }
        if(name.endsWith(".LivingEntity")){require(added==1&&removed==1&&detached==1&&forced==1&&cooldown==1&&writes==5,"environment living anchors added="+added+" removed="+removed+" detached="+detached+" forced="+forced+" cooldown="+cooldown+" writes="+writes);System.setProperty("jed.environment.living.coremod","verified");}
        else{require(ticked==1,"environment native effect tick");System.setProperty("jed.environment.effect.coremod","verified");}
        return node;
    }
}
