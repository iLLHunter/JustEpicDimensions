package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Version-pinned adapters only; formulas and policy remain ordinary custom classes. */
final class JEDTimeTransforms extends ArbitrageCoremod.Transformer {
    static final String COMMON="justepicdimensions/time/JEDTimeController",CLIENT="justepicdimensions/client/JEDClientTimeRuntime";
    static final List<String> CLASSES=List.of("net.minecraft.world.level.LevelTimeAccess","net.minecraft.world.level.Level","net.minecraft.server.level.ServerLevel","net.minecraft.client.multiplayer.ClientLevel","net.neoforged.neoforge.network.handlers.ClientPayloadHandler");
    private final String name;JEDTimeTransforms(String name){this.name=name;}
    static List<JEDTimeTransforms> all(){return CLASSES.stream().filter(name->!name.endsWith("ClientPayloadHandler") || net.neoforged.fml.loading.FMLLoader.getDist()!=net.neoforged.api.distmarker.Dist.DEDICATED_SERVER).map(JEDTimeTransforms::new).toList();}
    String target(){return name;}
    static MethodInsnNode bridge(String owner,String name,String descriptor){return new MethodInsnNode(Opcodes.INVOKESTATIC,owner,name,descriptor,false);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context) {
        // NeoForge resolves this handler's method references on both physical sides. Recomputing
        // its client-only frames on a dedicated server would force Minecraft client classes in.
        if(name.endsWith("ClientPayloadHandler") && net.neoforged.fml.loading.FMLLoader.getDist()==net.neoforged.api.distmarker.Dist.DEDICATED_SERVER)return c;
        int angles=0,moons=0,brightness=0,fixed=0,advances=0,sleeps=0,raw=0;
        for(var method:c.methods) {
            if(name.endsWith("LevelTimeAccess"))for(var node:method.instructions.toArray()) {
                boolean angle=method.name.equals("getTimeOfDay")&&method.desc.equals("(F)F")&&node.getOpcode()==Opcodes.FRETURN;
                boolean moon=method.name.equals("getMoonPhase")&&node.getOpcode()==Opcodes.IRETURN;
                boolean light=method.name.equals("getMoonBrightness")&&node.getOpcode()==Opcodes.FRETURN;
                if(angle||moon||light) {
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));
                    if(angle){out.add(new VarInsnNode(Opcodes.FLOAD,1));out.add(bridge(COMMON,"celestial","(FLnet/minecraft/world/level/LevelTimeAccess;F)F"));angles++;}
                    else if(moon){out.add(bridge(COMMON,"moon","(ILnet/minecraft/world/level/LevelTimeAccess;)I"));moons++;}
                    else{out.add(bridge(COMMON,"moonBrightness","(FLnet/minecraft/world/level/LevelTimeAccess;)F"));brightness++;}
                    method.instructions.insertBefore(node,out);
                }
            }
            for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call) {
                if(name.equals("net.minecraft.world.level.Level")&&(method.name.equals("isDay")||method.name.equals("isNight"))&&call.owner.endsWith("/DimensionType")&&call.name.equals("hasFixedTime")) {
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(bridge(COMMON,"fixedTime","(ZLnet/minecraft/world/level/Level;)Z"));method.instructions.insert(call,out);fixed++;
                }
                if((name.endsWith("ServerLevel")||name.endsWith("ClientLevel"))&&method.name.equals("tickTime")&&method.desc.equals("()V")&&call.name.equals("setDayTime")&&call.desc.equals("(J)V")) {
                    boolean server=name.endsWith("ServerLevel");method.instructions.set(call,bridge(server?COMMON:CLIENT,server?"serverAdvance":"advance","(L"+c.name+";J)V"));advances++;
                }
                if(name.endsWith("ServerLevel")&&method.name.equals("tick")&&call.owner.equals("net/neoforged/neoforge/event/EventHooks")&&call.name.equals("onSleepFinished")) {
                    require(call.desc.equals("(Lnet/minecraft/server/level/ServerLevel;JJ)J"),"sleep event signature");method.instructions.set(call,bridge(COMMON,"sleep",call.desc));sleeps++;
                }
                if(name.endsWith("ClientPayloadHandler")&&method.name.equals("handle")&&method.desc.startsWith("(Lnet/neoforged/neoforge/network/payload/ClientboundCustomSetTimePayload;")&&call.name.equals("setDayTime")&&call.desc.equals("(J)V")) {
                    method.instructions.set(call,bridge(CLIENT,"receivedRaw","(Lnet/minecraft/client/multiplayer/ClientLevel;J)V"));raw++;
                }
            }
            method.maxStack+=4;
        }
        if(name.endsWith("LevelTimeAccess"))require(angles==1&&moons==1&&brightness==1,name+" angle/moon/brightness anchors");
        else if(name.equals("net.minecraft.world.level.Level"))require(fixed==2,name+" fixed-time anchors");
        else if(name.endsWith("ServerLevel"))require(advances==1&&sleeps==1,name+" progression/sleep anchors");
        else if(name.endsWith("ClientLevel"))require(advances==1,name+" progression anchor");
        else require(raw==1,name+" explicit-rule time payload anchor");
        System.setProperty("jed.time.coremod."+name,"verified");return c;
    }
}
