package justepicdimensions.worldgen;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.structure.*;
import net.minecraft.core.*;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Long arithmetic keeps rejected offsets from wrapping before the native reference-range check. */
record JEDTemplateFootprint(long minX,long minY,long minZ,long maxX,long maxY,long maxZ,int height) {
 static JEDTemplateFootprint compute(JEDStructureDefinition definition,JEDStructureAssets.Asset asset,HolderLookup.Provider registries){
  var validated=JEDStructurePlan.validateAsset(definition,asset,registries);var plan=validated.plan();var size=plan.bounds();int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
  for(int x:new int[]{0,size.getXSpan()-1})for(int z:new int[]{0,size.getZSpan()-1}){var point=StructureTemplate.transform(new BlockPos(x,0,z),definition.mirror(),definition.rotation(),BlockPos.ZERO);x0=Math.min(x0,point.getX());x1=Math.max(x1,point.getX());z0=Math.min(z0,point.getZ());z1=Math.max(z1,point.getZ());}
  var embedded=StructureTemplate.transform(validated.embeddedOffset(),definition.mirror(),definition.rotation(),BlockPos.ZERO);long ox=8L+definition.offset().getX()+embedded.getX(),oz=8L+definition.offset().getZ()+embedded.getZ(),oy=(long)definition.offset().getY()+embedded.getY();
  if(definition.centered()){ox+=-Math.floorDiv(2*ox+x0+x1,2)+8L+definition.offset().getX();oz+=-Math.floorDiv(2*oz+z0+z1,2)+8L+definition.offset().getZ();}
  long minX=ox+x0,maxX=ox+x1,minZ=oz+z0,maxZ=oz+z1,minY=oy,maxY=oy+size.getYSpan()-1;
  for(var entity:plan.entities()){
   var block=StructureTemplate.transform(entity.blockPos,definition.mirror(),definition.rotation(),BlockPos.ZERO);var point=StructureTemplate.transform(entity.pos,definition.mirror(),definition.rotation(),BlockPos.ZERO);
   minX=Math.min(minX,ox+Math.min(block.getX(),(long)Math.floor(point.x)));maxX=Math.max(maxX,ox+Math.max(block.getX(),(long)Math.floor(point.x)));minZ=Math.min(minZ,oz+Math.min(block.getZ(),(long)Math.floor(point.z)));maxZ=Math.max(maxZ,oz+Math.max(block.getZ(),(long)Math.floor(point.z)));minY=Math.min(minY,oy+Math.min(block.getY(),(long)Math.floor(point.y)));maxY=Math.max(maxY,oy+Math.max(block.getY(),(long)Math.floor(point.y)));
  }
  return new JEDTemplateFootprint(minX,minY,minZ,maxX,maxY,maxZ,size.getYSpan());
 }
 void requireNativeRange(java.util.function.UnaryOperator<BoundingBox> nativeAdjustment,String asset){
  boolean outside= Math.floorDiv(minX,16)<-8||Math.floorDiv(maxX,16)>8||Math.floorDiv(minZ,16)<-8||Math.floorDiv(maxZ,16)>8;
  if(!outside){var box=nativeAdjustment.apply(new BoundingBox((int)minX,0,(int)minZ,(int)maxX,0,(int)maxZ));outside=(box.minX()>>4)<-8||(box.maxX()>>4)>8||(box.minZ()>>4)<-8||(box.maxZ()>>4)>8;}
  if(outside)throw new JEDDefinitionException(asset+": transformed repeatable footprint exceeds Minecraft's native eight-chunk structure-reference range. Reduce its footprint/offset or enable centering. Larger files remain supported for spawn placement.");
 }
 boolean fits(BlockPos anchor,int bottom,int top){long x=(long)anchor.getX()-8,z=(long)anchor.getZ()-8;return x+minX>=-29999984&&x+maxX<=29999984&&z+minZ>=-29999984&&z+maxZ<=29999984&&(long)anchor.getY()+minY>=bottom&&(long)anchor.getY()+maxY<top;}
 BoundingBox bounds(BlockPos anchor,BoundingBox blocks){long x=(long)anchor.getX()-8,z=(long)anchor.getZ()-8;return new BoundingBox((int)(x+minX),blocks.minY(),(int)(z+minZ),(int)(x+maxX),blocks.maxY(),(int)(z+maxZ));}
}
