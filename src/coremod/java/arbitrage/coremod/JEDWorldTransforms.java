package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Pinned call-site adapters. Ownership, persistence, commands and sync live in normal classes. */
final class JEDWorldTransforms extends ArbitrageCoremod.Transformer {
    static final String W="justepicdimensions/world/",C="justepicdimensions/client/",SERVER="Lnet/minecraft/server/MinecraftServer;",LEVEL="Lnet/minecraft/server/level/ServerLevel;",DATA="Lnet/minecraft/world/level/storage/ServerLevelData;",SOURCE="Lnet/minecraft/commands/CommandSourceStack;";
    static List<JEDWorldTransforms> all(){return java.util.stream.Stream.of("server.level.ServerLevel","server.MinecraftServer","server.players.PlayerList","server.level.ServerPlayer","server.network.ServerGamePacketListenerImpl","world.level.GameRules","world.level.GameRules$Value","server.ServerFunctionManager","server.commands.TimeCommand","server.commands.WeatherCommand","server.commands.WorldBorderCommand","server.commands.GameRuleCommand","server.commands.DifficultyCommand","server.commands.DefaultGameModeCommands","server.commands.ScheduleCommand","server.commands.SetWorldSpawnCommand","client.multiplayer.ClientLevel").map(name->new JEDWorldTransforms("net.minecraft."+name)).toList();}
    private final String name;JEDWorldTransforms(String name){this.name=name;}String target(){return name;}
    static MethodInsnNode hook(String owner,String name,String descriptor){return new MethodInsnNode(Opcodes.INVOKESTATIC,W+owner,name,descriptor,false);}
    static void start(MethodNode m,String owner,String name,String descriptor,int... locals){var out=new InsnList();for(int local:locals)out.add(new VarInsnNode(Opcodes.ALOAD,local));out.add(hook(owner,name,descriptor));m.instructions.insert(out);}
    static void getter(ClassNode c,String name,String field,String descriptor){var m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,name,"()"+descriptor,null,null);m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,field,descriptor));m.instructions.add(new InsnNode(descriptor.equals("Z")?Opcodes.IRETURN:Opcodes.ARETURN));m.maxStack=1;m.maxLocals=1;c.methods.add(m);}
    static void setter(ClassNode c,String name,String field,String descriptor){var m=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNTHETIC,name,"("+descriptor+")V",null,null);m.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));m.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));m.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,c.name,field,descriptor));m.instructions.add(new InsnNode(Opcodes.RETURN));m.maxStack=2;m.maxLocals=2;c.methods.add(m);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext ignored){int anchors=0;
        if(name.equals("net.minecraft.server.level.ServerLevel")){
            c.interfaces.add(W+"JEDLevelAccess");c.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNTHETIC,"jed$context","L"+W+"JEDLevelContext;",null,null));getter(c,"jed$context","jed$context","L"+W+"JEDLevelContext;");setter(c,"jed$context","jed$context","L"+W+"JEDLevelContext;");
            for(var m:c.methods){
                if(m.name.equals("<init>")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,4));out.add(new VarInsnNode(Opcodes.ALOAD,5));out.add(hook("JEDWorldControl","construct","("+SERVER+DATA+"Lnet/minecraft/resources/ResourceKey;)"+DATA));out.add(new VarInsnNode(Opcodes.ASTORE,4));out.add(new VarInsnNode(Opcodes.ILOAD,12));out.add(new VarInsnNode(Opcodes.ALOAD,4));out.add(hook("JEDWorldControl","tickTime","(Z"+DATA+")Z"));out.add(new VarInsnNode(Opcodes.ISTORE,12));m.instructions.insert(out);anchors++;
                    for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){
                        if(call.name.equals("setGameType")&&call.owner.endsWith("/ServerLevelData")){m.instructions.set(call,hook("JEDWorldControl","constructionMode","("+DATA+"Lnet/minecraft/world/level/GameType;)V"));anchors++;}
                        if(call.name.equals("getCustomSpawners")){var bind=new InsnList();bind.add(new VarInsnNode(Opcodes.ALOAD,0));bind.add(hook("JEDWorldControl","bind","("+LEVEL+")V"));m.instructions.insertBefore(call,bind);anchors++;}
                    }
                }
                if(m.name.equals("saveLevelData")&&m.desc.equals("()V")){start(m,"JEDWorldControl","save","("+LEVEL+")V",0);anchors++;}
                if(m.name.equals("tickTime"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.endsWith("/TimerQueue")&&call.name.equals("tick")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,hook("JEDFunctionContext","tick","(Lnet/minecraft/world/level/timers/TimerQueue;"+SERVER+"J"+LEVEL+")V"));anchors++;}
                if(m.name.equals("setDefaultSpawnPos"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.name.equals("broadcastAll")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,hook("JEDGameruleCallbacks","spawnPacket","(Lnet/minecraft/server/players/PlayerList;Lnet/minecraft/network/protocol/Packet;"+LEVEL+")V"));anchors++;}
            }require(anchors==6,name+" "+anchors);
        }else if(name.equals("net.minecraft.server.MinecraftServer")){
            for(var m:c.methods)for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){
                if(m.name.equals("createLevels")&&call.owner.endsWith("/WorldBorder")&&call.name.equals("addListener")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,0));m.instructions.set(call,hook("JEDWorldControl","delegate","(Lnet/minecraft/world/level/border/WorldBorder;Lnet/minecraft/world/level/border/BorderChangeListener;"+SERVER+")V"));anchors++;}
                if(m.name.equals("updateMobSpawningFlags")&&call.name.equals("setSpawnSettings")){m.instructions.set(call,hook("JEDWorldControl","spawnSettings","("+LEVEL+"ZZ)V"));anchors++;}
            }require(anchors==2,name);
        }else if(name.equals("net.minecraft.server.players.PlayerList")){
            for(var m:c.methods){
                if(m.name.equals("addWorldborderListener")){var next=new LabelNode();var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook("JEDWorldBorders","separate","(Lnet/minecraft/server/players/PlayerList;)Z"));out.add(new JumpInsnNode(Opcodes.IFEQ,next));out.add(new InsnNode(Opcodes.RETURN));out.add(next);m.instructions.insert(out);anchors++;}
                if(m.name.equals("sendLevelInfo")){for(var n:m.instructions.toArray()){
                    if(n instanceof MethodInsnNode call&&call.name.equals("overworld")){m.instructions.insertBefore(call,new VarInsnNode(Opcodes.ALOAD,2));m.instructions.set(call,hook("JEDWorldBorders","level","("+SERVER+LEVEL+")"+LEVEL));anchors++;}
                    if(n.getOpcode()==Opcodes.RETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(hook("JEDWorldSync","entry","(Lnet/minecraft/server/level/ServerPlayer;"+LEVEL+")V"));m.instructions.insertBefore(n,out);anchors++;}
                }}
            }require(anchors==3,name+" "+anchors);
        }else if(name.equals("net.minecraft.server.level.ServerPlayer")){
            for(var m:c.methods){if(m.name.equals("createCommonSpawnInfo")){start(m,"JEDWorldSync","beforeEntry","(Lnet/minecraft/server/level/ServerPlayer;"+LEVEL+")V",0,1);anchors++;}
                if(m.name.equals("calculateGameModeForNewPlayer"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&(call.name.equals("getDefaultGameType")||call.name.equals("getForcedGameType"))){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(hook("JEDWorldControl",call.name.equals("getDefaultGameType")?"defaultMode":"forcedMode","(Lnet/minecraft/world/level/GameType;Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/world/level/GameType;"));m.instructions.insert(call,out);anchors++;}
            }require(anchors==3,name);
        }else if(name.equals("net.minecraft.server.network.ServerGamePacketListenerImpl")){
            for(var m:c.methods)if(m.name.equals("handleClientCommand")){
                int captured=m.maxLocals++;
                for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){
                    if(call.name.equals("ensureRunningOnSameThread")){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"player","Lnet/minecraft/server/level/ServerPlayer;"));out.add(hook("JEDWorldControl","hardcoreDeath","(Lnet/minecraft/server/level/ServerPlayer;)Z"));out.add(new VarInsnNode(Opcodes.ISTORE,captured));m.instructions.insert(call,out);anchors++;}
                    if(call.name.equals("isHardcore")){var out=new InsnList();out.add(new InsnNode(Opcodes.POP));out.add(new VarInsnNode(Opcodes.ILOAD,captured));m.instructions.insertBefore(call,out);m.instructions.remove(call);anchors++;}
                }
            }require(anchors==2,name);
        }else if(name.equals("net.minecraft.world.level.GameRules$Value")){
            for(var m:c.methods)if(m.name.equals("onChanged"))for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.owner.equals("java/util/function/BiConsumer")&&call.name.equals("accept")){m.instructions.set(call,hook("JEDGameruleCallbacks","changed","(Ljava/util/function/BiConsumer;Ljava/lang/Object;Ljava/lang/Object;)V"));anchors++;}require(anchors==1,name);
        }else if(name.equals("net.minecraft.world.level.GameRules")){
            for(var m:c.methods)for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){if(call.name.equals("getPlayers")&&call.owner.endsWith("/PlayerList")){m.instructions.set(call,hook("JEDGameruleCallbacks","players","(Lnet/minecraft/server/players/PlayerList;)Ljava/util/List;"));anchors++;}if(call.name.equals("overworld")){m.instructions.set(call,hook("JEDGameruleCallbacks","level","("+SERVER+")"+LEVEL));anchors++;}}require(anchors==4,name+" "+anchors);
        }else if(name.equals("net.minecraft.server.ServerFunctionManager")){
            for(var m:c.methods)if(m.name.equals("getGameLoopSender"))for(var n:m.instructions.toArray())if(n.getOpcode()==Opcodes.ARETURN){m.instructions.insertBefore(n,hook("JEDFunctionContext","source","("+SOURCE+")"+SOURCE));anchors++;}require(anchors==1,name);
        }else if(name.startsWith("net.minecraft.server.commands.")){
            for(var m:c.methods){
                if(name.endsWith("SetWorldSpawnCommand")&&m.name.equals("setSpawn")){var next=new LabelNode();var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.FLOAD,2));out.add(hook("JEDWorldCommands","spawn","("+SOURCE+"Lnet/minecraft/core/BlockPos;F)I"));out.add(new InsnNode(Opcodes.DUP));out.add(new LdcInsnNode(Integer.MIN_VALUE));out.add(new JumpInsnNode(Opcodes.IF_ICMPEQ,next));out.add(new InsnNode(Opcodes.IRETURN));out.add(next);out.add(new InsnNode(Opcodes.POP));m.instructions.insert(out);anchors++;}
                boolean diff=name.endsWith("DifficultyCommand")&&m.name.equals("setDifficulty"),mode=name.endsWith("DefaultGameModeCommands")&&m.name.equals("setMode");
                if(diff||mode){var next=new LabelNode();var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(hook("JEDWorldCommands",diff?"difficulty":"mode","("+SOURCE+(diff?"Lnet/minecraft/world/Difficulty;":"Lnet/minecraft/world/level/GameType;")+")I"));out.add(new InsnNode(Opcodes.DUP));out.add(new LdcInsnNode(Integer.MIN_VALUE));out.add(new JumpInsnNode(Opcodes.IF_ICMPEQ,next));out.add(new InsnNode(Opcodes.IRETURN));out.add(next);out.add(new InsnNode(Opcodes.POP));m.instructions.insert(out);anchors++;}
                for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call){
                    String hook=null,desc=null;int local=0;boolean context=false;
                    if(name.endsWith("TimeCommand")&&call.name.equals("getAllLevels")){hook="levels";desc="("+SERVER+SOURCE+")Ljava/lang/Iterable;";}
                    if((name.endsWith("WeatherCommand")||name.endsWith("WorldBorderCommand"))&&call.name.equals("overworld")){hook="level";desc="("+SERVER+SOURCE+")"+LEVEL;}
                    if(name.endsWith("GameRuleCommand")&&call.name.equals("getGameRules")){hook="rules";desc="("+SERVER+SOURCE+")Lnet/minecraft/world/level/GameRules;";context=m.name.equals("setRule");}
                    if(name.endsWith("ScheduleCommand")&&call.name.equals("getScheduledEvents")){hook="timers";desc="("+DATA+SOURCE+")Lnet/minecraft/world/level/timers/TimerQueue;";context=m.desc.startsWith("(Lcom/mojang/brigadier/context/CommandContext;");}
                    if(hook!=null){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,local));if(context)out.add(hook("JEDWorldCommands","contextSource","(Lcom/mojang/brigadier/context/CommandContext;)"+SOURCE));m.instructions.insertBefore(call,out);m.instructions.set(call,hook("JEDWorldCommands",hook,desc));anchors++;}
                }
            }
            int expected=name.endsWith("TimeCommand")?2:name.endsWith("WeatherCommand")?4:name.endsWith("WorldBorderCommand")?7:name.endsWith("GameRuleCommand")?2:name.endsWith("ScheduleCommand")?3:1;require(anchors==expected,name+" "+anchors+" expected "+expected);
        }else if(name.equals("net.minecraft.client.multiplayer.ClientLevel")){
            for(var m:c.methods)if(m.name.equals("<init>")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new VarInsnNode(Opcodes.ALOAD,3));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,C+"JEDClientWorldRuntime","construct","(Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;",false));out.add(new VarInsnNode(Opcodes.ASTORE,2));m.instructions.insert(out);anchors++;
                for(var n:m.instructions.toArray())if(n instanceof MethodInsnNode call&&call.name.equals("prepareWeather")){var bind=new InsnList();bind.add(new VarInsnNode(Opcodes.ALOAD,0));bind.add(new MethodInsnNode(Opcodes.INVOKESTATIC,C+"JEDClientWorldRuntime","bind","(Lnet/minecraft/client/multiplayer/ClientLevel;)V",false));m.instructions.insert(call,bind);anchors++;}
            }require(anchors==2,name);
        }
        require(anchors>0,name);for(var m:c.methods)m.maxStack+=8;System.setProperty("jed.world.coremod."+name,"verified");return c;
    }
}
