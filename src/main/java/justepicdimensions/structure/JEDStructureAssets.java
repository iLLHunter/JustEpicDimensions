package justepicdimensions.structure;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.JEDDimensionFiles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Immutable binary snapshots: resource streams never escape preparation or survive a reload. */
public final class JEDStructureAssets {
    public record Asset(byte[] bytes,String source,String sha256){
        public Asset {bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    private final Map<ResourceLocation,Asset> assets;
    private final List<String> conflicts;
    private JEDStructureAssets(Map<ResourceLocation,Asset> assets,List<String> conflicts){this.assets=Map.copyOf(assets);this.conflicts=List.copyOf(conflicts);}
    public static JEDStructureAssets capture(ResourceManager packs,JEDConfigRepository.Selection selection,Collection<ResourceLocation> requested){
        var files=new TreeMap<ResourceLocation,Asset>();var conflicts=new ArrayList<String>();
        Path data=selection.source().resolve("data");
        for(var id:new TreeSet<>(requested))try{
            var stack=packs.getResourceStack(id);
            if(!stack.isEmpty()){
                var resource=stack.getLast();try(var input=resource.open()){files.put(id,asset(input.readAllBytes(),"datapack:"+resource.sourcePackId()));}
                if(stack.size()>1)conflicts.add(id+": datapack "+resource.sourcePackId()+" overrides "+stack.subList(0,stack.size()-1).stream().map(r->r.sourcePackId()).toList());
            }
            Path file=data.resolve(id.getNamespace()).resolve(id.getPath());JEDDimensionFiles.requireSafe(data,file,false);
            if(Files.isRegularFile(file)){var replacement=asset(Files.readAllBytes(file),"config:"+file);var old=files.put(id,replacement);if(old!=null)conflicts.add(id+": "+replacement.source()+" overrides "+old.source());}
        }catch(IOException error){throw new JEDDefinitionException("Cannot snapshot structure "+id,error);}
        return new JEDStructureAssets(files,conflicts);
    }
    private static Asset asset(byte[] bytes,String source){try{return new Asset(bytes,source,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    public JEDStructureAssets overrides(Map<ResourceLocation,byte[]> replacements){var result=new TreeMap<>(assets);replacements.forEach((id,bytes)->result.put(id,asset(bytes,"bundle preview")));return new JEDStructureAssets(result,conflicts);}
    public Asset get(ResourceLocation id){var asset=assets.get(id);if(asset==null)throw new JEDDefinitionException("Missing structure asset "+id);return asset;}
    public List<String> conflicts(){return conflicts;}
    public String fingerprint(){var text=new StringBuilder();new TreeMap<>(assets).forEach((id,asset)->text.append(id).append('=').append(asset.sha256()).append('@').append(asset.source()).append('\n'));return text.append(conflicts).toString();}
    public static JEDStructureAssets creation(JEDStructureAssets current,JEDStructureAssets authored,JEDStructureDefinition definition){return creation(current,authored,definition,List.of());}
    public static JEDStructureAssets creation(JEDStructureAssets current,JEDStructureAssets authored,JEDStructureDefinition definition,Collection<ResourceLocation> worldgen){var map=new TreeMap<>(current.assets);if(definition!=null)map.putIfAbsent(definition.resource(),authored.get(definition.resource()));for(var resource:worldgen){var asset=authored.assets.get(resource);if(asset!=null)map.putIfAbsent(resource,asset);}return new JEDStructureAssets(map,authored.conflicts);}
}
