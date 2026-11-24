package arbitrage.coremod;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;

/** Version-pinned entry/consumption adapters; policy and terrain work remain ordinary Java. */
final class JEDSpawnTransforms extends ArbitrageCoremod.Transformer {
    static final String S="justepicdimensions/spawn/",P="Lnet/minecraft/server/level/ServerPlayer;",L="Lnet/minecraft/server/level/ServerLevel;",LEVEL="Lnet/minecraft/world/level/Level;",POS="Lnet/minecraft/core/BlockPos;",KEY="Lnet/minecraft/resources/ResourceKey;",DT="Lnet/minecraft/world/level/portal/DimensionTransition;",POST="Lnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;",DATA="Lnet/minecraft/world/level/storage/ServerLevelData;";
    final String name;JEDSpawnTransforms(String name){this.name=name;}String target(){return name;}
    static List<JEDSpawnTransforms> all(){return java.util.stream.Stream.of("server.players.PlayerList","server.level.ServerPlayer","server.level.ServerLevel","world.level.Level","world.level.block.BedBlock","network.Connection","server.network.ServerCommonPacketListenerImpl","server.network.ServerGamePacketListenerImpl","client.server.IntegratedServer").map(n->new JEDSpawnTransforms("net.minecraft."+n)).toList();}
    static MethodInsnNode bridge(String name,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,S+"JEDSpawnController",name,desc,false);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){int anchors=0;
        if(name.endsWith(".IntegratedServer")){
            for(var m:c.methods)if(m.name.equals("tickServer")&&m.desc.equals("(Ljava/util/function/BooleanSupplier;)V"))
                for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/client/Minecraft")&&call.name.equals("isPaused")&&call.desc.equals("()Z")){
                    var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge("pauseDuringLogin","(ZLnet/minecraft/server/MinecraftServer;)Z"));
                    m.instructions.insert(call,code);m.maxStack=Math.max(m.maxStack,3);anchors++;
                }
        }else if(name.endsWith(".PlayerList")){for(var m:c.methods){
            if(m.name.equals("placeNewPlayer"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals(c.name)&&call.name.equals("load")){
                m.instructions.set(call,bridge("loginLoad","(Lnet/minecraft/server/players/PlayerList;"+P+")Ljava/util/Optional;"));
                var code=new InsnList();var ready=new LabelNode();code.add(new InsnNode(Opcodes.DUP));for(int i:new int[]{1,2,0,3})code.add(new VarInsnNode(Opcodes.ALOAD,i));code.add(bridge("awaitLogin","(Ljava/util/Optional;Lnet/minecraft/network/Connection;"+P+"Lnet/minecraft/server/players/PlayerList;Lnet/minecraft/server/network/CommonListenerCookie;)Z"));code.add(new JumpInsnNode(Opcodes.IFEQ,ready));code.add(new InsnNode(Opcodes.POP));code.add(new InsnNode(Opcodes.RETURN));code.add(ready);
                // The replacement node occupies the old call's list position.
                var replacement=Arrays.stream(m.instructions.toArray()).filter(x->x instanceof MethodInsnNode mc&&mc.owner.equals(S+"JEDSpawnController")&&mc.name.equals("loginLoad")).findFirst().orElseThrow();m.instructions.insert(replacement,code);anchors++;
            }
            if(m.name.equals("placeNewPlayer"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/network/Connection")&&call.name.equals("setupInboundProtocol")){m.instructions.set(call,bridge("loginProtocol","(Lnet/minecraft/network/Connection;Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/PacketListener;)V"));anchors++;}
            if(m.name.equals("respawn")){var code=new InsnList();var ready=new LabelNode();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new VarInsnNode(Opcodes.ILOAD,2));code.add(new VarInsnNode(Opcodes.ALOAD,3));code.add(bridge("deferRespawn","(Lnet/minecraft/server/players/PlayerList;"+P+"ZLnet/minecraft/world/entity/Entity$RemovalReason;)Z"));code.add(new JumpInsnNode(Opcodes.IFEQ,ready));code.add(new VarInsnNode(Opcodes.ALOAD,1));code.add(new InsnNode(Opcodes.ARETURN));code.add(ready);m.instructions.insert(code);anchors++;}m.maxStack=Math.max(m.maxStack,10);
        }}
        else if(name.endsWith(".ServerPlayer")){
            c.interfaces.add(S+"JEDSpawnPlayerAccess");
            var forward=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,"jed$resolveSpawn","("+L+POS+"FZZ)Ljava/util/Optional;",null,null);for(int i:new int[]{1,2})forward.instructions.add(new VarInsnNode(Opcodes.ALOAD,i));forward.instructions.add(new VarInsnNode(Opcodes.FLOAD,3));forward.instructions.add(new VarInsnNode(Opcodes.ILOAD,4));forward.instructions.add(new VarInsnNode(Opcodes.ILOAD,5));forward.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,c.name,"findRespawnAndUseSpawnBlock","("+L+POS+"FZZ)Ljava/util/Optional;",false));forward.instructions.add(new InsnNode(Opcodes.ARETURN));forward.maxStack=5;forward.maxLocals=6;c.methods.add(forward);
            require(c.methods.stream().anyMatch(m->m.name.equals("findRespawnAndUseSpawnBlock")&&m.desc.equals("("+L+POS+"FZZ)Ljava/util/Optional;")),"native respawn helper");
            for(var m:c.methods){
                if(m.name.equals("findRespawnPositionAndUseSpawnBlock")){var code=new InsnList();var nativePath=new LabelNode();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(new VarInsnNode(Opcodes.ILOAD,1));code.add(new VarInsnNode(Opcodes.ALOAD,2));code.add(bridge("route","("+P+"Z"+POST+")"+DT));code.add(new InsnNode(Opcodes.DUP));code.add(new JumpInsnNode(Opcodes.IFNULL,nativePath));code.add(new InsnNode(Opcodes.ARETURN));code.add(nativePath);code.add(new InsnNode(Opcodes.POP));m.instructions.insert(code);anchors++;}
                if(m.name.equals("startSleepInBed")||m.name.contains("lambda$startSleepInBed"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){
                    if(call.name.equals("natural")&&call.owner.endsWith("/DimensionType")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,bridge("sleepNatural","(Lnet/minecraft/world/level/dimension/DimensionType;"+P+")Z"));anchors++;}
                    if(call.name.equals("setRespawnPosition")){m.instructions.set(call,bridge("bedCheckpoint","("+P+KEY+POS+"FZZ)V"));anchors++;}
                }
                if(m.name.equals("setRespawnPosition"))for(var n:m.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN){var code=new InsnList();for(int i:new int[]{0,1,2})code.add(new VarInsnNode(Opcodes.ALOAD,i));code.add(bridge("checkpoint","("+P+KEY+POS+")V"));m.instructions.insertBefore(n,code);anchors++;}
                if(m.name.equals("die")){var returns=Arrays.stream(m.instructions.toArray()).filter(n->n.getOpcode()==Opcodes.RETURN).toList();require(returns.size()==2,"cancelled/committed player death returns");var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge("died","("+P+")V"));m.instructions.insertBefore(returns.getLast(),code);anchors++;}
                m.maxStack=Math.max(m.maxStack,10);
            }
        }else if(name.endsWith(".ServerGamePacketListenerImpl")) {
            for(var m:c.methods)if(m.name.equals("<init>"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/server/network/TextFilter")&&call.name.equals("join")&&call.desc.equals("()V")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,bridge("joinFilter","(Lnet/minecraft/server/network/TextFilter;Lnet/minecraft/server/network/ServerGamePacketListenerImpl;)V"));m.maxStack=Math.max(m.maxStack,4);anchors++;}
        }else if(name.endsWith(".ServerCommonPacketListenerImpl")) {
            var fields=Map.of("keepAliveTime","J","keepAlivePending","Z","keepAliveChallenge","J","latency","I","closed","Z");fields.forEach((field,descriptor)->require(c.fields.stream().anyMatch(f->f.name.equals(field)&&f.desc.equals(descriptor)),"native common listener field "+field));
            c.interfaces.add(S+"JEDSpawnCommonAccess");var m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,"jed$handoffTo","(L"+c.name+";)Z",null,null);var open=new LabelNode();m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"closed","Z"));m.instructions.add(new JumpInsnNode(Opcodes.IFEQ,open));m.instructions.add(new InsnNode(Opcodes.ICONST_0));m.instructions.add(new InsnNode(Opcodes.IRETURN));m.instructions.add(open);
            for(String field:List.of("keepAliveTime","keepAlivePending","keepAliveChallenge","latency")){m.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,field,fields.get(field)));m.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,c.name,field,fields.get(field)));}m.instructions.add(new InsnNode(Opcodes.ICONST_1));m.instructions.add(new InsnNode(Opcodes.IRETURN));m.maxStack=3;m.maxLocals=2;c.methods.add(m);anchors++;
        }else if(name.endsWith(".Connection")) {
            String info="Lnet/minecraft/network/ProtocolInfo;",listener="Lnet/minecraft/network/PacketListener;";
            require(c.fields.stream().filter(f->(f.name.equals("packetListener")||f.name.equals("disconnectListener"))&&f.desc.equals(listener)).count()==2,"native connection listener fields");
            require(c.methods.stream().anyMatch(m->m.name.equals("validateListener")&&m.desc.equals("("+info+listener+")V")),"native listener validator");
            c.interfaces.add(S+"JEDSpawnConnectionAccess");var m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,"jed$replacePlayListener","("+info+listener+")V",null,null);
            for(int i:new int[]{0,1,2})m.instructions.add(new VarInsnNode(Opcodes.ALOAD,i));m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,c.name,"validateListener","("+info+listener+")V",false));
            m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,2));m.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,c.name,"packetListener",listener));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));m.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,c.name,"disconnectListener",listener));m.instructions.add(new InsnNode(Opcodes.RETURN));m.maxStack=3;m.maxLocals=3;c.methods.add(m);anchors++;
        }else if(name.endsWith(".Level")) {
            for(var m:c.methods)if(m.name.equals("getSharedSpawnPos")||m.name.equals("getSharedSpawnAngle"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&(call.name.equals("getSpawnPos")||call.name.equals("getSpawnAngle"))){var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge(call.name.equals("getSpawnPos")?"spawnPosition":"spawnAngle",call.name.equals("getSpawnPos")?"("+POS+LEVEL+")"+POS:"(F"+LEVEL+")F"));m.instructions.insert(call,code);m.maxStack=Math.max(m.maxStack,5);anchors++;}
        }else if(name.endsWith(".ServerLevel")) {
            for(var m:c.methods)if(m.name.equals("setDefaultSpawnPos"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){if(call.name.equals("setSpawn")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,bridge("setSpawn","("+DATA+POS+"F"+L+")V"));anchors++;}else if(call.name.equals("getSpawnPos")||call.name.equals("getSpawnAngle")){var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge(call.name.equals("getSpawnPos")?"spawnPosition":"spawnAngle",call.name.equals("getSpawnPos")?"("+POS+LEVEL+")"+POS:"(F"+LEVEL+")F"));m.instructions.insert(call,code);anchors++;}m.maxStack=Math.max(m.maxStack,10);}
        }else if(name.endsWith(".BedBlock")) {
            for(var m:c.methods){if(m.name.equals("canSetSpawn"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.name.equals("bedWorks")){var code=new InsnList();code.add(new VarInsnNode(Opcodes.ALOAD,0));code.add(bridge("bedWorks","(Z"+LEVEL+")Z"));m.instructions.insert(call,code);anchors++;}
                if(m.name.equals("useWithoutItem")){var ready=new LabelNode();var code=new InsnList();for(int i:new int[]{2,4,3})code.add(new VarInsnNode(Opcodes.ALOAD,i));code.add(bridge("denyBed","("+LEVEL+"Lnet/minecraft/world/entity/player/Player;"+POS+")Z"));code.add(new JumpInsnNode(Opcodes.IFEQ,ready));code.add(new FieldInsnNode(Opcodes.GETSTATIC,"net/minecraft/world/InteractionResult","SUCCESS","Lnet/minecraft/world/InteractionResult;"));code.add(new InsnNode(Opcodes.ARETURN));code.add(ready);m.instructions.insert(code);anchors++;}m.maxStack=Math.max(m.maxStack,10);}
        }
        int expected=name.endsWith(".ServerPlayer")?6:name.endsWith(".ServerLevel")||name.endsWith(".PlayerList")?3:name.endsWith(".Connection")||name.endsWith(".ServerCommonPacketListenerImpl")||name.endsWith(".ServerGamePacketListenerImpl")||name.endsWith(".IntegratedServer")?1:2;require(anchors==expected,name+" spawn anchors "+anchors+" expected "+expected);System.setProperty("jed.spawn.coremod."+name,"verified");return c;
    }
}
