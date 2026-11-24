package arbitrage.coremod;

import java.util.*;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Change actual consumers, without changing DimensionType.ultraWarm/natural globally. */
final class JEDProviderTransforms extends ArbitrageCoremod.Transformer {
    static final String COMMON="justepicdimensions/provider/JEDProviderController",LEVEL="Lnet/minecraft/world/level/Level;";
    private final String name;JEDProviderTransforms(String name){this.name=name;}
    static List<JEDProviderTransforms> all(){return java.util.stream.Stream.of("net.minecraft.world.item.CompassItem","net.minecraft.world.level.block.NetherPortalBlock","net.minecraft.client.renderer.item.ItemProperties$1","net.minecraft.world.item.BucketItem","net.minecraft.world.level.block.IceBlock","net.neoforged.neoforge.fluids.FluidType","net.minecraft.world.level.saveddata.maps.MapItemSavedData","net.minecraft.client.multiplayer.ClientLevel$ClientLevelData","net.minecraft.client.renderer.LevelRenderer").map(JEDProviderTransforms::new).toList();}
    String target(){return name;}
    public ClassNode transform(ClassNode c,ITransformerVotingContext ignored){int count=0;
        for(var m:c.methods){for(var n:m.instructions.toArray()){
            if(n instanceof MethodInsnNode call&&call.name.equals("dimensionType")&&call.desc.equals("()Lnet/minecraft/world/level/dimension/DimensionType;")&&JEDWorldgenTransforms.real(n.getNext()) instanceof MethodInsnNode predicate&&Set.of("natural","ultraWarm").contains(predicate.name)){
                m.instructions.insertBefore(call,new InsnNode(Opcodes.DUP));var out=new InsnList();out.add(new InsnNode(Opcodes.SWAP));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,predicate.name.equals("natural")?"surface":"vaporizes","(Z"+LEVEL+")Z",false));m.instructions.insert(predicate,out);count++;
            }
            if(name.endsWith("MapItemSavedData")&&m.name.equals("addDecoration")&&n instanceof FieldInsnNode field&&field.getOpcode()==Opcodes.GETSTATIC&&field.owner.equals("net/minecraft/world/level/Level")&&field.name.equals("NETHER")){
                var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new FieldInsnNode(Opcodes.GETFIELD,c.name,"dimension","Lnet/minecraft/resources/ResourceKey;"));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"mapSpinDimension","(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/LevelAccessor;)Lnet/minecraft/resources/ResourceKey;",false));m.instructions.insertBefore(field,out);m.instructions.remove(field);count++;
            }
            if(name.endsWith("ClientLevel$ClientLevelData")&&m.name.equals("getHorizonHeight")&&n.getOpcode()==Opcodes.DRETURN){var out=new InsnList();out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,COMMON,"horizon","(DLnet/minecraft/world/level/LevelHeightAccessor;)D",false));m.instructions.insertBefore(n,out);count++;}
            if(name.endsWith("LevelRenderer")&&n instanceof MethodInsnNode call&&call.name.equals("isFoggyAt")&&call.desc.equals("(II)Z")){m.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,"justepicdimensions/client/JEDClientProviderRuntime","fog","(Z)Z",false));count++;}
        }m.maxStack+=4;}
        require(count==(name.endsWith("IceBlock")?2:1),"provider consumer "+name+" "+count);System.setProperty("jed.provider.coremod."+name,"verified");return c;
    }
}
