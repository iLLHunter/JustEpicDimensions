package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Native cause/provenance and successful entity-registration adapters. Policy remains ordinary Java. */
final class JEDMobRulesTransforms extends ArbitrageCoremod.Transformer {
    private static final String BRIDGE="justepicdimensions/mob/JEDMobRulesController",SOURCE="justepicdimensions/mob/JEDMobSpawnSource",ENTITY="Lnet/minecraft/world/entity/Entity;",LEVEL="Lnet/minecraft/server/level/ServerLevel;",ACCESS="Lnet/minecraft/world/level/ServerLevelAccessor;",WEIGHTS="Lnet/minecraft/util/random/WeightedRandomList;";
    final String name;JEDMobRulesTransforms(String name){this.name=name;}String target(){return name;}
    static List<JEDMobRulesTransforms> all(){return java.util.stream.Stream.of("world.entity.Entity","server.level.ServerLevel","world.level.entity.PersistentEntitySectionManager","world.level.entity.PersistentEntitySectionManager$Callback","world.entity.Mob","world.level.NaturalSpawner","server.commands.SummonCommand","world.item.SpawnEggItem","server.level.WorldGenRegion").map(n->new JEDMobRulesTransforms("net.minecraft."+n)).toList();}
    private static MethodInsnNode bridge(String method,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,BRIDGE,method,desc,false);}
    public ClassNode transform(ClassNode type,ITransformerVotingContext context){int anchors=0;
        if(name.equals("net.minecraft.world.entity.Entity")){
            require(type.fields.stream().anyMatch(f->f.name.equals("persistentData")&&f.desc.equals("Lnet/minecraft/nbt/CompoundTag;")),"NeoForge entity persistent data field");
            type.interfaces.add("justepicdimensions/mob/JEDMobPersistentAccess");var accessor=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,"jed$mobPersistentData","()Lnet/minecraft/nbt/CompoundTag;",null,null);accessor.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));accessor.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,"persistentData","Lnet/minecraft/nbt/CompoundTag;"));accessor.instructions.add(new InsnNode(Opcodes.ARETURN));accessor.maxLocals=1;accessor.maxStack=1;type.methods.add(accessor);
            System.setProperty("jed.mob.rules.entity","verified");
        }else if(name.equals("net.minecraft.server.level.ServerLevel")){
            for(var m:type.methods)if(m.name.equals("addDuringTeleport")&&m.desc.equals("("+ENTITY+")V")){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(bridge("restored","("+ENTITY+")V"));m.instructions.insert(code);m.maxStack=Math.max(m.maxStack,1);anchors++;
            }
            require(anchors==1,"one native existing-entity teleport admission");System.setProperty("jed.mob.rules.travel","verified");
        }else if(name.equals("net.minecraft.world.level.entity.PersistentEntitySectionManager")){
            for(var m:type.methods)if(m.name.equals("addEntityWithoutEvent")&&m.desc.equals("(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode invoke&&invoke.name.equals("setLevelCallback")&&invoke.desc.equals("(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V")){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.ICONST_1));code.add(bridge("registered","(Ljava/lang/Object;Z)V"));m.instructions.insert(n,code);m.maxStack=Math.max(m.maxStack,2);anchors++;
            }
            require(anchors==1,"one committed native UUID/section registration");System.setProperty("jed.mob.rules.registry","verified");
        }else if(name.endsWith("PersistentEntitySectionManager$Callback")){
            var field=type.fields.stream().filter(f->f.name.equals("entity")&&f.desc.equals("Lnet/minecraft/world/level/entity/EntityAccess;")).findFirst().orElseThrow(()->new IllegalStateException("Missing native entity removal owner"));
            for(var m:type.methods)if(m.name.equals("onRemove")&&m.desc.equals("(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"))for(var n:m.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new FieldInsnNode(Opcodes.GETFIELD,type.name,field.name,field.desc));code.add(new InsnNode(Opcodes.ICONST_0));code.add(bridge("registered","(Ljava/lang/Object;Z)V"));m.instructions.insertBefore(n,code);m.maxStack=Math.max(m.maxStack,2);anchors++;
            }
            require(anchors==1,"one completed native entity removal");
        }else if(name.equals("net.minecraft.world.entity.Mob")){
            for(var m:type.methods)if(m.name.equals("convertTo"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode invoke&&invoke.owner.equals("net/minecraft/world/entity/EntityType")&&invoke.name.equals("create")&&invoke.desc.equals("(Lnet/minecraft/world/level/Level;)"+ENTITY)){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new InsnNode(Opcodes.SWAP));code.add(bridge("conversion","("+ENTITY+ENTITY+")"+ENTITY));m.instructions.insert(n,code);m.maxStack=Math.max(m.maxStack,4);anchors++;
            }
            require(anchors==1,"native conversion factory before inventory mutation");
        }else if(name.endsWith("NaturalSpawner")){
            for(var m:type.methods)if(m.name.equals("spawnMobsForChunkGeneration"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode invoke&&invoke.owner.equals("net/minecraft/world/level/biome/MobSpawnSettings")&&invoke.name.equals("getMobs")&&invoke.desc.endsWith(WEIGHTS)){
                var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge("generationList","("+WEIGHTS+ACCESS+")"+WEIGHTS));m.instructions.insert(n,code);m.maxStack=Math.max(m.maxStack,4);anchors++;
            }
            require(anchors==1,"native initial animal spawn list");
        }else if(name.endsWith("SummonCommand")||name.endsWith("SpawnEggItem")){
            boolean command=name.endsWith("SummonCommand");String method=command?"tryAddFreshEntityWithPassengers":"addFreshEntityWithPassengers";
            for(var m:type.methods)for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode invoke&&invoke.owner.equals("net/minecraft/server/level/ServerLevel")&&invoke.name.equals(method)&&invoke.desc.equals("("+ENTITY+")"+(command?"Z":"V"))){
                m.instructions.insertBefore(n,new FieldInsnNode(Opcodes.GETSTATIC,SOURCE,command?"COMMAND":"SPAWN_EGG","L"+SOURCE+";"));m.instructions.set(n,bridge(command?"addGroup":"addFreshGroup","("+LEVEL+ENTITY+"L"+SOURCE+";)"+(command?"Z":"V")));m.maxStack=Math.max(m.maxStack,4);anchors++;
            }
            require(anchors==1,"native "+method+" source adapter");
        }else if(name.endsWith("WorldGenRegion")){
            for(var m:type.methods)if(m.name.equals("addFreshEntity")&&m.desc.equals("("+ENTITY+")Z")){
                var code=new InsnList();var allowed=new LabelNode();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(bridge("generating","("+ACCESS+ENTITY+")Z"));code.add(new JumpInsnNode(Opcodes.IFNE,allowed));code.add(new InsnNode(Opcodes.ICONST_0));code.add(new InsnNode(Opcodes.IRETURN));code.add(allowed);m.instructions.insert(code);m.maxStack=Math.max(m.maxStack,2);anchors++;
            }
            require(anchors==1,"native generated-entity serialization admission");
        }else throw new IllegalStateException(name);
        return type;
    }
}
