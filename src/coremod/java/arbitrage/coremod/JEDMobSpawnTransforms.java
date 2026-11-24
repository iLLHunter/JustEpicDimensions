package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

final class JEDMobSpawnTransforms extends ArbitrageCoremod.Transformer {
    static final String COMMON="justepicdimensions/mob/JEDMobSpawnController",LEVEL="Lnet/minecraft/server/level/ServerLevel;";
    private final String name;JEDMobSpawnTransforms(String name){this.name=name;}
    static List<JEDMobSpawnTransforms> all(){return List.of(new JEDMobSpawnTransforms("net.minecraft.server.level.ServerChunkCache"),new JEDMobSpawnTransforms("net.minecraft.server.level.ServerLevel"),new JEDMobSpawnTransforms("net.minecraft.world.level.NaturalSpawner"),new JEDMobSpawnTransforms("net.minecraft.world.level.NaturalSpawner$SpawnState"),new JEDMobSpawnTransforms("net.minecraft.world.level.LocalMobCapCalculator$MobCounts"));}
    String target(){return name;}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context) {
        if(name.equals("net.minecraft.world.level.NaturalSpawner")){
            int scopes=0;
            for(var method:c.methods)if(method.name.equals("spawnForChunk")&&method.desc.startsWith("("+LEVEL)){
                int previous=method.maxLocals++,error=method.maxLocals++;var start=new LabelNode();var end=new LabelNode();var handler=new LabelNode();
                var enter=new InsnList();enter.add(new VarInsnNode(Opcodes.ALOAD,0));enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"enterNatural","("+LEVEL+")"+LEVEL,false));enter.add(new VarInsnNode(Opcodes.ASTORE,previous));enter.add(start);method.instructions.insert(enter);
                for(var node:method.instructions.toArray())if(node.getOpcode()==Opcodes.RETURN){var exit=new InsnList();exit.add(new VarInsnNode(Opcodes.ALOAD,previous));exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"exitNatural","("+LEVEL+")V",false));method.instructions.insertBefore(node,exit);}
                method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new VarInsnNode(Opcodes.ASTORE,error));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,previous));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"exitNatural","("+LEVEL+")V",false));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,error));method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));method.maxStack=Math.max(method.maxStack,2);scopes++;
            }
            require(scopes==1,"one finally-scoped native natural spawning call");return c;
        }
        if(name.endsWith("NaturalSpawner$SpawnState")){
            int caps=0;for(var method:c.methods)if(method.name.equals("canSpawnForCategory"))for(var node:method.instructions.toArray())if(node.getOpcode()==Opcodes.IDIV){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"spawnableChunkCount","I"));out.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/level/NaturalSpawner","MAGIC_NUMBER","I"));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"globalCap","(ILnet/minecraft/world/entity/MobCategory;II)I",false));method.instructions.insert(node,out);method.maxStack+=4;caps++;
            }require(caps==1,"one dimension-scaled native category cap");return c;
        }
        if(name.endsWith("LocalMobCapCalculator$MobCounts")){
            int caps=0;for(var method:c.methods)if(method.name.equals("canSpawn"))for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/entity/MobCategory")&&call.name.equals("getMaxInstancesPerChunk")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"localCap","(ILnet/minecraft/world/entity/MobCategory;)I",false));method.instructions.insert(node,out);method.maxStack+=1;caps++;
            }require(caps==1,"one local-player native category cap");return c;
        }
        int hostile=0,peaceful=0,retention=0;
        for(var method:c.methods)for(var node:method.instructions.toArray()) {
            if(name.endsWith("ServerChunkCache")&&method.name.equals("setSpawnSettings")&&method.desc.equals("(ZZ)V")&&node instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.owner.equals(c.name)&&field.desc.equals("Z")) {
                boolean enemy=field.name.equals("spawnEnemies"),friendly=field.name.equals("spawnFriendlies");
                if(enemy||friendly){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"level",LEVEL));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,enemy?"hostile":"peaceful","(Z"+LEVEL+")Z",false));method.instructions.insertBefore(field,out);if(enemy)hostile++;else peaceful++;method.maxStack+=1;}
            }
            if(name.endsWith("ServerLevel")&&method.name.equals("shouldDiscardEntity")&&method.desc.equals("(Lnet/minecraft/world/entity/Entity;)Z")&&node instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/server/MinecraftServer")&&call.name.equals("isSpawningAnimals")&&call.desc.equals("()Z")) {
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"animalsRetained","(Z"+LEVEL+")Z",false));method.instructions.insert(call,out);retention++;method.maxStack+=1;
            }
        }
        if(name.endsWith("ServerChunkCache"))require(hostile==1&&peaceful==1,"mob spawn settings anchors");else require(retention==1,"mob animal-retention anchor");
        System.setProperty("jed.mob.coremod."+name,"verified");return c;
    }
}
