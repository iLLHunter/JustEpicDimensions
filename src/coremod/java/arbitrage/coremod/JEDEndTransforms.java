package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class JEDEndTransforms extends ArbitrageCoremod.Transformer {
    static final String END="justepicdimensions/end/JEDEndController",LEVEL="Lnet/minecraft/server/level/ServerLevel;",KEY="Lnet/minecraft/resources/ResourceKey;";
    private final String name;JEDEndTransforms(String name){this.name=name;}
    static List<JEDEndTransforms> all(){return java.util.stream.Stream.of("server.level.ServerLevel","world.level.levelgen.feature.SpikeFeature","world.level.dimension.end.DragonRespawnAnimation$3","world.level.block.EndPortalBlock").map(n->new JEDEndTransforms("net.minecraft."+n)).toList();}
    String target(){return name;}
    static MethodInsnNode hook(String method,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,END,method,desc,false);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext ignored){int eligibility=0,bind=0,save=0,spikes=0,rebuild=0,portal=0;
        for(var m:c.methods){
            if(name.endsWith("SpikeFeature")&&m.name.equals("place")&&m.desc.equals("(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")){
                var next=new LabelNode();var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"net/minecraft/world/level/levelgen/feature/FeaturePlaceContext","level","()Lnet/minecraft/world/level/WorldGenLevel;",false));out.add(hook("generateSpikes","(Lnet/minecraft/world/level/WorldGenLevel;)Z"));out.add(new JumpInsnNode(Opcodes.IFNE,next));out.add(new InsnNode(Opcodes.ICONST_0));out.add(new InsnNode(Opcodes.IRETURN));out.add(next);m.instructions.insert(out);spikes++;
            }
            if(name.endsWith("DragonRespawnAnimation$3")&&m.name.equals("tick")){
                var next=new LabelNode();var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(hook("skipReconstruction","("+LEVEL+"Lnet/minecraft/world/level/dimension/end/EndDragonFight;)Z"));out.add(new JumpInsnNode(Opcodes.IFEQ,next));out.add(new InsnNode(Opcodes.RETURN));out.add(next);m.instructions.insert(out);rebuild++;
            }
            for(var n:m.instructions.toArray()){
                if(name.endsWith("ServerLevel")){
                    if(m.name.equals("<init>")&&n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals("net/minecraft/world/level/Level")&&field.name.equals("END")){m.instructions.insertBefore(field,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(field,hook("nativeEndKey","("+LEVEL+")"+KEY));eligibility++;}
                    if(m.name.equals("<init>")&&n instanceof MethodInsnNode call&&call.name.equals("getCustomSpawners")){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook("bind","("+LEVEL+")V"));m.instructions.insertBefore(call,out);bind++;}
                    if(m.name.equals("saveLevelData")&&n instanceof MethodInsnNode call&&call.name.equals("setEndDragonFightData")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,hook("save","(Lnet/minecraft/world/level/storage/WorldData;Lnet/minecraft/world/level/dimension/end/EndDragonFight$Data;"+LEVEL+")V"));save++;}
                }
                if((name.endsWith("EndPortalBlock")||name.endsWith("EndCrystalItem"))&&n instanceof MethodInsnNode call&&call.name.equals("dimension")&&call.desc.equals("()"+KEY)){
                    m.instructions.insertBefore(call,new InsnNode(Opcodes.DUP));var out=new InsnList();out.add(new InsnNode(Opcodes.SWAP));out.add(hook("endDimension","("+KEY+"Lnet/minecraft/world/level/Level;)"+KEY));m.instructions.insert(call,out);portal++;
                }
            }m.maxStack+=3;
        }
        if(name.endsWith("ServerLevel"))require(eligibility==1&&bind==1&&save==1,"End construction/save anchors "+eligibility+","+bind+","+save);
        else if(name.endsWith("SpikeFeature"))require(spikes==1,"spike generation anchor");
        else if(name.endsWith("DragonRespawnAnimation$3"))require(rebuild==1,"spike reconstruction anchor");
        else require(portal==(name.endsWith("EndPortalBlock")?2:1),"End provider consumer "+name+" "+portal);
        System.setProperty("jed.end.coremod."+name,"verified");return c;
    }
}
