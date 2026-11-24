package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned constructor bridges: no generator wrapper or initialized final-field replacement. */
final class JEDWorldgenTransforms extends ArbitrageCoremod.Transformer {
    static final String GEN="justepicdimensions/worldgen/",WORLD="justepicdimensions/world/",SERVER="Lnet/minecraft/server/MinecraftServer;",LEVEL="Lnet/minecraft/server/level/ServerLevel;",KEY="Lnet/minecraft/resources/ResourceKey;";
    private final String name; JEDWorldgenTransforms(String name){this.name=name;}
    static List<JEDWorldgenTransforms> all(){return java.util.stream.Stream.of("server.level.ServerLevel","world.level.Level","server.MinecraftServer","server.level.PlayerRespawnLogic","server.level.ChunkMap","world.level.levelgen.WorldDimensions","world.level.levelgen.NoiseBasedChunkGenerator","world.level.chunk.status.ChunkStatusTasks","world.level.levelgen.structure.pieces.StructurePieceSerializationContext").map(n->new JEDWorldgenTransforms("net.minecraft."+n)).toList();}
    String target(){return name;}
    static MethodInsnNode hook(String owner,String method,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,owner,method,desc,false);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext ignored){int prepare=0,bind=0,seed=0,check=0,options=0,borders=0,spawn=0,changed=0,random=0,flat=0,structures=0,structureStates=0,contexts=0,samplers=0;
        if(name.endsWith("ServerLevel")){
            c.interfaces.add(GEN+"JEDWorldgenAccess");c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC,"jed$worldgen","L"+GEN+"JEDWorldgenContext;",null,null));
            JEDWorldTransforms.getter(c,"jed$worldgen","jed$worldgen","L"+GEN+"JEDWorldgenContext;");JEDWorldTransforms.setter(c,"jed$worldgen","jed$worldgen","L"+GEN+"JEDWorldgenContext;");
        }
        for(var m:c.methods){
            if(name.endsWith("ServerLevel")&&m.name.equals("<init>")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,5));out.add(new VarInsnNode(Opcodes.LLOAD,9));out.add(hook(GEN+"JEDWorldgenContext","prepare","("+SERVER+KEY+"J)J"));out.add(new VarInsnNode(Opcodes.LSTORE,9));m.instructions.insert(out);prepare++;
            }
            for(var n:m.instructions.toArray()){
                if(name.endsWith("NoiseBasedChunkGenerator")&&m.name.equals("doCreateBiomes")&&n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/levelgen/NoiseChunk")&&call.name.equals("cachedClimateSampler")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(hook(GEN+"JEDWeightedBiomeSource","chunkSampler","(Lnet/minecraft/world/level/biome/Climate$Sampler;Lnet/minecraft/world/level/levelgen/RandomState;)Lnet/minecraft/world/level/biome/Climate$Sampler;"));m.instructions.insert(call,out);samplers++;
                }
                if(name.endsWith("StructurePieceSerializationContext")&&m.name.equals("fromLevel")&&n.getOpcode()==Opcodes.ARETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(GEN+"JEDTemplateStructure","serializationContext","(Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;"+LEVEL+")Lnet/minecraft/world/level/levelgen/structure/pieces/StructurePieceSerializationContext;"));m.instructions.insertBefore(n,out);contexts++;}
                if(name.endsWith("ChunkMap")&&m.name.equals("<init>")&&n instanceof MethodInsnNode state&&state.owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")&&state.name.equals("createState")){
                    // Preserve the INVOKEVIRTUAL anchor for Moonlight/other WrapOperation mixins.
                    int generator=m.maxLocals++,sets=m.maxLocals++,randomState=m.maxLocals++,worldSeed=m.maxLocals;m.maxLocals+=2;
                    var before=new InsnList();before.add(new VarInsnNode(Opcodes.LSTORE,worldSeed));before.add(new VarInsnNode(Opcodes.ASTORE,randomState));before.add(new VarInsnNode(Opcodes.ASTORE,sets));before.add(new VarInsnNode(Opcodes.ASTORE,generator));
                    before.add(new VarInsnNode(Opcodes.ALOAD,sets));before.add(new VarInsnNode(Opcodes.ALOAD,1));before.add(hook(GEN+"JEDGeneratorFactory","structureSets","(Lnet/minecraft/core/HolderLookup;"+LEVEL+")Lnet/minecraft/core/HolderLookup;"));before.add(new VarInsnNode(Opcodes.ASTORE,sets));
                    before.add(new VarInsnNode(Opcodes.ALOAD,generator));before.add(new VarInsnNode(Opcodes.ALOAD,sets));before.add(new VarInsnNode(Opcodes.ALOAD,randomState));before.add(new VarInsnNode(Opcodes.LLOAD,worldSeed));m.instructions.insertBefore(state,before);
                    var after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,generator));after.add(new VarInsnNode(Opcodes.ALOAD,sets));after.add(new VarInsnNode(Opcodes.ALOAD,randomState));after.add(new VarInsnNode(Opcodes.LLOAD,worldSeed));after.add(new VarInsnNode(Opcodes.ALOAD,1));after.add(hook(GEN+"JEDGeneratorFactory","structureState","(Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/HolderLookup;Lnet/minecraft/world/level/levelgen/RandomState;J"+LEVEL+")Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;"));m.instructions.insert(state,after);structureStates++;
                }
                if(name.endsWith("ChunkStatusTasks")&&m.name.equals("generateStructureStarts")&&n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/levelgen/WorldOptions")&&call.name.equals("generateStructures")){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(GEN+"JEDWorldgenContext","structures","(ZLnet/minecraft/world/level/chunk/status/WorldGenContext;)Z"));m.instructions.insert(call,out);structures++;}
                if(name.endsWith("ChunkMap")&&m.name.equals("<init>")&&n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.PUTFIELD&&field.name.equals("randomState")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,8));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(hook(GEN+"JEDGeneratorFactory","randomState","(Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkGenerator;"+LEVEL+")Lnet/minecraft/world/level/levelgen/RandomState;"));m.instructions.insertBefore(field,out);random++;
                }
                if(name.endsWith("WorldDimensions")&&n instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.INSTANCEOF&&type.desc.equals("net/minecraft/world/level/levelgen/FlatLevelSource")){m.instructions.set(type,hook(GEN+"JEDGeneratorFactory","isFlat","(Lnet/minecraft/world/level/chunk/ChunkGenerator;)Z"));flat++;}
                if(name.endsWith("ServerLevel")&&m.name.equals("<init>")){
                    if(n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(c.name)&&f.name.equals("server")){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(GEN+"JEDWorldgenContext","bind","("+LEVEL+")V"));m.instructions.insert(f,out);bind++;}
                    if(n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/storage/WorldData")&&call.name.equals("worldGenOptions")&&options++==1){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(GEN+"JEDWorldgenContext","options","(Lnet/minecraft/world/level/levelgen/WorldOptions;"+LEVEL+")Lnet/minecraft/world/level/levelgen/WorldOptions;"));m.instructions.insert(call,out);}
                    if(n instanceof MethodInsnNode call&&call.name.equals("getBiomeSource")&&real(n.getNext()) instanceof VarInsnNode load&&load.getOpcode()==Opcodes.LLOAD){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,c.name,"getSeed","()J",false));m.instructions.insertBefore(load,out);m.instructions.remove(load);check++;}
                }
                if(name.endsWith("ServerLevel")&&m.name.equals("getSeed")&&n.getOpcode()==Opcodes.LRETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(GEN+"JEDWorldgenContext","seed","(J"+LEVEL+")J"));m.instructions.insertBefore(n,out);seed++;}
                if(name.endsWith("ServerLevel")&&m.name.equals("setDefaultSpawnPos")&&n.getOpcode()==Opcodes.RETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook(WORLD+"JEDBorderController","spawnChanged","("+LEVEL+")V"));m.instructions.insertBefore(n,out);changed++;}
                if(name.equals("net.minecraft.world.level.Level")&&m.name.equals("<init>")&&n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("worldBorder")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,3));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new VarInsnNode(Opcodes.ILOAD,6));out.add(hook(WORLD+"JEDBorderController","construct","(Lnet/minecraft/world/level/border/WorldBorder;Lnet/minecraft/core/RegistryAccess;"+KEY+"Z)Lnet/minecraft/world/level/border/WorldBorder;"));m.instructions.insertBefore(f,out);borders++;
                }
                if((name.endsWith("MinecraftServer")||name.endsWith("PlayerRespawnLogic"))&&n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/chunk/ChunkGenerator")&&call.name.equals("getSpawnHeight")){
                    // The original LevelHeightAccessor is the sole argument and is also needed by the policy.
                    var out=new InsnList();out.add(new InsnNode(Opcodes.DUP));int local=m.maxLocals++;out.add(new VarInsnNode(Opcodes.ASTORE,local));m.instructions.insertBefore(call,out);
                    var after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,local));after.add(hook("justepicdimensions/provider/JEDProviderController","spawnHeight","(ILnet/minecraft/world/level/LevelHeightAccessor;)I"));m.instructions.insert(call,after);spawn++;
                }
            }
            m.maxStack+=6;
        }
        if(name.endsWith("ServerLevel"))require(prepare==1&&bind==1&&seed==1&&check==1&&options==2&&changed==1,"generation constructor anchors: "+prepare+","+bind+","+seed+","+check+","+options+","+changed);
        else if(name.equals("net.minecraft.world.level.Level"))require(borders==2,"native scaled border construction");
        else if(name.endsWith("ChunkMap"))require(random==2&&structureStates==1,"native generator state construction "+random+","+structureStates);
        else if(name.endsWith("NoiseBasedChunkGenerator"))require(samplers==1,"native noise-chunk biome sampler "+samplers);
        else if(name.endsWith("WorldDimensions"))require(flat==1,"native flat world classification "+flat);
        else if(name.endsWith("StructurePieceSerializationContext"))require(contexts==1,"native dimension structure context "+contexts);
        else if(name.endsWith("ChunkStatusTasks"))require(structures==1,"native structure-start generation gate "+structures);
        else require(spawn==1,"native default spawn height: "+name+" "+spawn);
        System.setProperty("jed.worldgen.coremod."+name,"verified");return c;
    }
    static AbstractInsnNode real(AbstractInsnNode n){while(n!=null&&n.getOpcode()<0)n=n.getNext();return n;}
}
