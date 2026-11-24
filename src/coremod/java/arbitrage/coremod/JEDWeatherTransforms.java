package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Small call-site adapters; native weather simulation, random ticking and packet delivery stay native. */
final class JEDWeatherTransforms extends ArbitrageCoremod.Transformer {
    static final String COMMON="justepicdimensions/weather/JEDWeatherController";
    static final String READER="Lnet/minecraft/world/level/LevelReader;",POS="Lnet/minecraft/core/BlockPos;",BIOME="Lnet/minecraft/world/level/biome/Biome;",LEVEL="Lnet/minecraft/server/level/ServerLevel;";
    private final String name;JEDWeatherTransforms(String name){this.name=name;}
    static List<JEDWeatherTransforms> all(){return List.of(new JEDWeatherTransforms("net.minecraft.server.level.ServerLevel"),new JEDWeatherTransforms("net.minecraft.world.level.biome.Biome"));}
    String target(){return name;}
    static MethodInsnNode bridge(String name,String descriptor){return new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,name,descriptor,false);}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context) {
        int lightning=0,sleep=0,freeze=0,snow=0,precipitation=0,temperatures=0;
        for(var method:c.methods) {
            for(var node:method.instructions.toArray())if(node instanceof MethodInsnNode call) {
                if(name.endsWith("ServerLevel")) {
                    if(method.name.equals("tickChunk")&&call.name.equals("isThundering")&&call.desc.equals("()Z")) {
                        var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(bridge("lightning","(Z"+LEVEL+")Z"));method.instructions.insert(call,out);lightning++;
                    }
                    if(method.name.equals("tick")&&call.name.equals("resetWeatherCycle")&&call.desc.equals("()V")) {
                        method.instructions.set(call,bridge("resetAfterSleep","("+LEVEL+")V"));sleep++;
                    }
                    if(method.name.equals("tickPrecipitation")&&call.owner.equals("net/minecraft/world/level/biome/Biome")) {
                        if(call.name.equals("shouldFreeze")&&call.desc.equals("("+READER+POS+")Z")){method.instructions.set(call,bridge("freezeTick","("+BIOME+READER+POS+")Z"));freeze++;}
                        if(call.name.equals("shouldSnow")&&call.desc.equals("("+READER+POS+")Z")){method.instructions.set(call,bridge("snowTick","("+BIOME+READER+POS+")Z"));snow++;}
                        if(call.name.equals("getPrecipitationAt")&&call.desc.equals("("+POS+")Lnet/minecraft/world/level/biome/Biome$Precipitation;")) {
                            var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(bridge("precipitation","(Lnet/minecraft/world/level/biome/Biome$Precipitation;"+LEVEL+")Lnet/minecraft/world/level/biome/Biome$Precipitation;"));method.instructions.insert(call,out);precipitation++;
                        }
                    }
                } else if((method.name.equals("shouldFreeze")&&method.desc.equals("("+READER+POS+"Z)Z")||method.name.equals("shouldSnow")&&method.desc.equals("("+READER+POS+")Z"))&&call.name.equals("warmEnoughToRain")&&call.desc.equals("("+POS+")Z")) {
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new InsnNode(method.name.equals("shouldSnow")?Opcodes.ICONST_1:Opcodes.ICONST_0));out.add(bridge("warmEnough","(Z"+READER+"Z)Z"));method.instructions.insert(call,out);temperatures++;
                }
            }
            method.maxStack+=3;
        }
        if(name.endsWith("ServerLevel"))require(lightning==1&&sleep==1&&freeze==1&&snow==1&&precipitation==1,"weather ServerLevel anchors");
        else require(temperatures==2,"weather Biome temperature anchors");
        System.setProperty("jed.weather.coremod."+name,"verified");return c;
    }
}
