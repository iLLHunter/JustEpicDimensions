package justepicdimensions.structure;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public record JEDStructureDefinition(ResourceLocation asset, Rotation rotation, Mirror mirror,
                                     boolean centered, BlockPos offset, int loadAround, boolean pasteBiomes) {
    public static JEDStructureDefinition decode(JsonElement value, String where) {
        var json=JEDJson.object(value,where);
        JEDJson.keys(json,Set.of("asset","rotation","mirror","centered","offset","load_around","paste_biomes"),where);
        var asset=JEDDimensionDefinition.id(JEDJson.string(json.get("asset"),where+".asset"),where+".asset");
        if(!asset.getPath().endsWith(".nbt")&&!asset.getPath().endsWith(".schem"))throw new JEDDefinitionException(where+": asset must end in .nbt or .schem");
        Rotation rotation=Rotation.NONE;Mirror mirror=Mirror.NONE;
        try {
            if(json.has("rotation"))rotation=Rotation.valueOf(JEDJson.string(json.get("rotation"),where+".rotation").toUpperCase(Locale.ROOT));
            if(json.has("mirror"))mirror=Mirror.valueOf(JEDJson.string(json.get("mirror"),where+".mirror").toUpperCase(Locale.ROOT));
        }catch(IllegalArgumentException error){throw new JEDDefinitionException(where+": invalid rotation or mirror",error);}
        BlockPos offset=BlockPos.ZERO;
        if(json.has("offset")){
            if(!json.get("offset").isJsonArray()||json.getAsJsonArray("offset").size()!=3)throw new JEDDefinitionException(where+".offset must contain three integers");
            var xyz=json.getAsJsonArray("offset");offset=new BlockPos(JEDJson.integer(xyz.get(0),where+".offset[0]"),JEDJson.integer(xyz.get(1),where+".offset[1]"),JEDJson.integer(xyz.get(2),where+".offset[2]"));
        }
        int around=json.has("load_around")?JEDJson.integer(json.get("load_around"),where+".load_around"):16;
        if(around<0||around>128)throw new JEDDefinitionException(where+".load_around must be between 0 and 128 blocks");
        return new JEDStructureDefinition(asset,rotation,mirror,bool(json,"centered",false,where),offset,around,bool(json,"paste_biomes",false,where));
    }
    private static boolean bool(JsonObject json,String key,boolean fallback,String where){if(!json.has(key))return fallback;var value=json.get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new JEDDefinitionException(where+"."+key+": expected a boolean");return value.getAsBoolean();}
    public ResourceLocation resource(){return ResourceLocation.fromNamespaceAndPath(asset.getNamespace(),(asset.getPath().endsWith(".nbt")?"structure/":"justepicdimensions/schematics/")+asset.getPath());}
}
