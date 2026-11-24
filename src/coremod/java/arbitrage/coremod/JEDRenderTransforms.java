package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Pinned consumers which lack a per-level NeoForge event; no rendering algorithm lives here. */
final class JEDRenderTransforms extends ArbitrageCoremod.Transformer {
    private static final String CLIENT="justepicdimensions/client/JEDClientRenderRuntime",SKY="justepicdimensions/client/JEDSkyRenderer";
    private static final String CL="Lnet/minecraft/client/multiplayer/ClientLevel;",VEC="Lnet/minecraft/world/phys/Vec3;";
    private final String name;JEDRenderTransforms(String name){this.name=name;}
    static List<JEDRenderTransforms> all(){return java.util.stream.Stream.of("net.minecraft.world.level.Level","net.minecraft.client.multiplayer.ClientLevel","net.minecraft.client.renderer.LevelRenderer").map(JEDRenderTransforms::new).toList();}
    String target(){return name;}
    public ClassNode transform(ClassNode c,ITransformerVotingContext context){var counts=new HashMap<String,Integer>();
        for(var m:c.methods){Boolean body=null;for(var n:m.instructions.toArray()){
            if(name.equals("net.minecraft.world.level.Level")&&m.name.equals("updateSkyBrightness")&&n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("skyDarken")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/render/JEDRenderController","skyDarken","(ILnet/minecraft/world/level/Level;)I",false));m.instructions.insertBefore(n,out);hit(counts,"sun_factor");
            }
            // Sky effects as other mods see them (Iris picks a pack's End shaders by DimensionType.effectsLocation); the bridge
            // returns the native type except for the active client level whose JED sky mode differs from it.
            if(name.equals("net.minecraft.world.level.Level")&&m.name.equals("dimensionType")&&m.desc.equals("()Lnet/minecraft/world/level/dimension/DimensionType;")&&n.getOpcode()==Opcodes.ARETURN){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/render/JEDRenderController","dimensionType","(Lnet/minecraft/world/level/dimension/DimensionType;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/level/dimension/DimensionType;",false));m.instructions.insertBefore(n,out);hit(counts,"dimension_type");
            }
            if(name.endsWith("ClientLevel")){
                if(m.name.equals("effects")&&n.getOpcode()==Opcodes.ARETURN){ret(m,n,"effects","(Lnet/minecraft/client/renderer/DimensionSpecialEffects;"+CL+")Lnet/minecraft/client/renderer/DimensionSpecialEffects;");hit(counts,"effects");}
                if(m.name.equals("getCloudColor")&&n.getOpcode()==Opcodes.ARETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.FLOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,CLIENT,"cloudColor","("+VEC+CL+"F)"+VEC,false));m.instructions.insertBefore(n,out);hit(counts,"cloud");}
                if(m.name.equals("getSkyColor")&&n.getOpcode()==Opcodes.ARETURN){ret(m,n,"fixedSky","("+VEC+CL+")"+VEC);hit(counts,"sky_fixed");}
                if(m.name.equals("getSkyColor")&&n instanceof MethodInsnNode call&&call.name.equals("gaussianSampleVec3")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,CLIENT,"skyBase","("+VEC+CL+")"+VEC,false));m.instructions.insert(n,out);hit(counts,"sky_base");
                }
                if(m.name.equals("getSkyColor")&&n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/util/Mth")&&call.name.equals("clamp")&&call.desc.equals("(FFF)F")){
                    var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,CLIENT,"skyLight","(F"+CL+")F",false));m.instructions.insert(n,out);hit(counts,"sky_light");
                }
                if((m.name.equals("getStarBrightness")||m.name.equals("getSkyDarken")&&m.desc.equals("(F)F"))&&n.getOpcode()==Opcodes.FRETURN){ret(m,n,m.name.equals("getStarBrightness")?"stars":"sunBrightness","(F"+CL+")F");hit(counts,m.name);}
                if(m.name.equals("calculateBlockTint")&&n instanceof MethodInsnNode call&&call.owner.equals("net/minecraft/world/level/ColorResolver")&&call.name.equals("getColor")){
                    m.instructions.insertBefore(n,new VarInsnNode(Opcodes.ALOAD,0));call.setOpcode(Opcodes.INVOKESTATIC);call.owner="justepicdimensions/client/JEDBiomeColorController";call.name="color";call.desc="(Lnet/minecraft/world/level/ColorResolver;Lnet/minecraft/world/level/biome/Biome;DD"+CL+")I";call.itf=false;hit(counts,"biome");
                }
            }
            if(name.endsWith("LevelRenderer")&&m.name.equals("renderSky")){
                if(n instanceof LdcInsnNode literal&&literal.cst instanceof Float value&&(value==30F||value==20F)){
                    var out=level(c.name);out.add(new InsnNode(value==20F?Opcodes.ICONST_1:Opcodes.ICONST_0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SKY,"size","(F"+CL+"Z)F",false));m.instructions.insert(n,out);hit(counts,value==20F?"moon_size":"sun_size");
                }
                if(n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.GETSTATIC&&(f.name.equals("SUN_LOCATION")||f.name.equals("MOON_LOCATION"))){
                    boolean moon=f.name.equals("MOON_LOCATION");var tint=level(c.name);tint.add(new InsnNode(moon?Opcodes.ICONST_1:Opcodes.ICONST_0));tint.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SKY,"tint","("+CL+"Z)V",false));m.instructions.insertBefore(n,tint);
                    var out=level(c.name);out.add(new InsnNode(moon?Opcodes.ICONST_1:Opcodes.ICONST_0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SKY,"texture","(Lnet/minecraft/resources/ResourceLocation;"+CL+"Z)Lnet/minecraft/resources/ResourceLocation;",false));m.instructions.insert(n,out);hit(counts,moon?"moon_texture":"sun_texture");body=moon;
                }
                if(body!=null&&n instanceof MethodInsnNode call&&call.getOpcode()==Opcodes.INVOKESTATIC&&call.owner.equals("com/mojang/blaze3d/vertex/BufferUploader")&&call.name.equals("drawWithShader")&&call.desc.equals("(Lcom/mojang/blaze3d/vertex/MeshData;)V")){
                    // The native draw stays in place: Iris (MixinLevelRenderer_SunMoonToggle) and other mods wrap this exact call.
                    var out=new InsnList();out.add(new InsnNode(Opcodes.DUP));out.add(level(c.name));out.add(new InsnNode(body?Opcodes.ICONST_1:Opcodes.ICONST_0));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SKY,"darken","(Lcom/mojang/blaze3d/vertex/MeshData;"+CL+"Z)V",false));m.instructions.insertBefore(call,out);hit(counts,body?"moon_draw":"sun_draw");body=null;
                }
            }
        }}
        var expected=name.equals("net.minecraft.world.level.Level")?Map.of("sun_factor",1,"dimension_type",1):name.endsWith("ClientLevel")?Map.of("effects",1,"cloud",1,"sky_fixed",1,"sky_base",1,"sky_light",1,"getStarBrightness",1,"getSkyDarken",1,"biome",2):Map.of("sun_size",1,"moon_size",1,"sun_texture",1,"moon_texture",1,"sun_draw",1,"moon_draw",1);
        require(counts.equals(expected),"Arbitrage rendering "+name+" "+counts+" expected "+expected);System.setProperty("arbitrage.render.coremod."+name,"verified");return c;
    }
    private static InsnList level(String owner){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,owner,"level",CL));return out;}
    private static void hit(Map<String,Integer> counts,String key){counts.merge(key,1,Integer::sum);}
    private static void ret(MethodNode m,AbstractInsnNode n,String name,String desc){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,CLIENT,name,desc,false));m.instructions.insertBefore(n,out);}
}
