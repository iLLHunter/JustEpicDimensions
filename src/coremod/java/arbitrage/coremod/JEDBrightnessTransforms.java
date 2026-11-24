package arbitrage.coremod;

import cpw.mods.modlauncher.api.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Proven brightness prototype's native consumers, without any fixture instrumentation. */
final class JEDBrightnessTransforms implements ITransformer<ClassNode> {
    static final List<String> CLASSES=List.of("net.minecraft.world.level.LevelReader","net.minecraft.client.renderer.LightTexture","net.minecraft.client.renderer.ScreenEffectRenderer","net.minecraft.client.renderer.entity.EntityRenderDispatcher","net.minecraft.client.gui.Gui");
    public Set<Target<ClassNode>> targets(){var targets=new HashSet<Target<ClassNode>>();CLASSES.forEach(name->targets.add(Target.targetClass(name)));return targets;}
    public TargetType<ClassNode> getTargetType(){return TargetType.CLASS;}
    public TransformerVoteResult castVote(ITransformerVotingContext context){return TransformerVoteResult.YES;}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){
        int changes=0;
        for(var method:type.methods){
            if(type.name.equals("net/minecraft/world/level/LevelReader") && method.name.equals("getLightLevelDependentMagicValue") && method.desc.equals("(Lnet/minecraft/core/BlockPos;)F")){
                int rawSlot=method.maxLocals++,reads=0,returns=0;
                for(var node:method.instructions.toArray()){
                    if(node instanceof MethodInsnNode call && call.name.equals("getMaxLocalRawBrightness") && call.desc.equals("(Lnet/minecraft/core/BlockPos;)I")){
                        var capture=new InsnList();capture.add(new InsnNode(Opcodes.DUP));capture.add(new VarInsnNode(Opcodes.ISTORE,rawSlot));method.instructions.insert(node,capture);reads++;
                    }
                    if(node.getOpcode()==Opcodes.FRETURN){
                        var hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,0));hook.add(new VarInsnNode(Opcodes.ILOAD,rawSlot));
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/lighting/JEDBrightnessTables","common","(FLnet/minecraft/world/level/LevelReader;I)F",false));method.instructions.insertBefore(node,hook);returns++;changes++;
                    }
                }
                ArbitrageCoremod.Transformer.require(reads==1 && returns==1,"brightness common raw read / return");
            }
            String expectedMethod=type.name.endsWith("/LightTexture")?"updateLightTexture":type.name.endsWith("/ScreenEffectRenderer")?"renderFluid":type.name.endsWith("/EntityRenderDispatcher")?"renderBlockShadow":type.name.endsWith("/Gui")?"updateVignetteBrightness":null;
            if(expectedMethod==null || !method.name.equals(expectedMethod))continue;
            for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/LightTexture") && call.name.equals("getBrightness") && call.desc.equals("(Lnet/minecraft/world/level/dimension/DimensionType;I)F")){
                MethodInsnNode dimensionType=null;
                for(var previous=node.getPrevious();previous!=null;previous=previous.getPrevious())if(previous instanceof MethodInsnNode candidate && candidate.name.equals("dimensionType") && candidate.desc.equals("()Lnet/minecraft/world/level/dimension/DimensionType;")){dimensionType=candidate;break;}
                ArbitrageCoremod.Transformer.require(dimensionType!=null,"brightness consumer level context "+type.name);
                method.instructions.remove(dimensionType);call.owner="justepicdimensions/client/JEDClientBrightness";call.name="lookup";call.desc="(Lnet/minecraft/world/level/LevelReader;I)F";changes++;
            }
        }
        int expected=type.name.endsWith("/LightTexture")?2:1;
        ArbitrageCoremod.Transformer.require(changes==expected,"brightness "+type.name+" expected "+expected+", found "+changes);
        System.setProperty("jed.brightness.coremod."+type.name.replace('/','.'),"verified");return type;
    }
}
