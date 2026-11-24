package justepicdimensions.client;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.definition.JEDDefinitionException;
import justepicdimensions.structure.JEDTemplateManager;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** CreateWorldScreen can pass an already-baked UI world directly, bypassing WorldLoader's data supplier. */
public final class JEDClientTemplateLoading {
    public static WorldStem stem(WorldStem original,WorldOpenFlows flows,PackRepository packs){
        var imported=JEDTemplateManager.takeImported(original.resourceManager());if(imported==null)return original;
        original.close();
        try{
            // Reuse the complete native loader, including template datapack worldgen registries.
            var result=flows.loadWorldStem(new Dynamic<>(NbtOps.INSTANCE,imported),false,packs);
            justepicdimensions.definition.JEDInfoLog.info(((justepicdimensions.definition.JEDDefinitionResources)result.resourceManager()).prepared().controls(),"JED rebuilt the integrated world's native registries and settings from imported template metadata");
            return result;
        }catch(Exception error){throw new JEDDefinitionException("Cannot load imported Overworld template metadata",error);}
    }
    @EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{try{Class.forName("net.minecraft.client.gui.screens.worldselection.WorldOpenFlows",false,JEDClientTemplateLoading.class.getClassLoader());}catch(ClassNotFoundException error){throw new IllegalStateException(error);}if(!"verified".equals(System.getProperty("jed.template.coremod.client")))throw justepicdimensions.JustEpicDimensions.arbitrageMissing("integrated template","single-player world loading");});}
    }
    private JEDClientTemplateLoading(){}
}
