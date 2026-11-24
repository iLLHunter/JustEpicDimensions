package justepicdimensions.client;

import justepicdimensions.JustEpicDimensions;
import justepicdimensions.lighting.JEDBrightnessTables;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.LevelReader;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Preserve native downstream lightmap math, gamma and NeoForge callbacks. */
@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDClientBrightness {
    public static float lookup(LevelReader level,int index) {
        var table=JEDBrightnessTables.table(level);
        return table==null?LightTexture.getBrightness(level.dimensionType(),index):table.value(index);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(()->{
        for (String name:new String[]{"net.minecraft.client.renderer.LightTexture","net.minecraft.client.renderer.ScreenEffectRenderer","net.minecraft.client.renderer.entity.EntityRenderDispatcher","net.minecraft.client.gui.Gui"}) {
            try { Class.forName(name,false,JEDClientBrightness.class.getClassLoader()); } catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
            if (!"verified".equals(System.getProperty("jed.brightness.coremod."+name))) throw justepicdimensions.JustEpicDimensions.arbitrageMissing("brightness",name);
        }
    }); }
    private JEDClientBrightness() {}
}
