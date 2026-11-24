package justepicdimensions.client;

import com.mojang.blaze3d.platform.NativeImage;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.render.JEDRenderController;
import justepicdimensions.render.JEDRenderDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The current dimension's authored sky, cloud and fog settings as a 16x1 texture, so shader packs that draw their own
 * sky can follow them. A pack names it in {@code shaders.properties}:
 * {@code customTexture.jedSkyColors = justepicdimensions:textures/sky/shader_pack_colors.png}. Iris fetches named
 * textures from the texture manager on every bind, so this runtime texture reaches the pack and updates without a
 * shader reload. The layout is documented in docs/RENDERING.md ("Shader packs"); pixels 0 to 3 keep their version 1
 * meaning. Colours are sRGB; a pixel's alpha is 255 when the dimension sets that value and 0 when it leaves it native.
 */
@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDShaderPackColors {
    public static final ResourceLocation LOCATION=ResourceLocation.fromNamespaceAndPath(JustEpicDimensions.ID,"textures/sky/shader_pack_colors.png");
    static final int VERSION=2,WIDTH=16;
    static final int SUN=0,MOON=1,MARKER=2,LAYOUT=3,SKY=4,FOG=5,CLOUDS=6,FLAGS=7,SUN_SCALE=8,MOON_SCALE=9,CLOUD_HEIGHT=10,FOG_NEAR=11,FOG_FAR=12,BRIGHTNESS=13;
    static final int STARS_HIDDEN=1,SUN_HIDDEN=2,MOON_HIDDEN=4,CLOUDS_HIDDEN=8,FOG_SPHERE=16,FOG_CYLINDER=32,SKY_NONE=64,SKY_END=128,WATER_COLORS=256;
    private static DynamicTexture texture;
    private static int[] uploaded;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();var manager=mc.getTextureManager();
        if(texture==null){texture=new DynamicTexture(new NativeImage(WIDTH,1,true));uploaded=null;}
        // A resource reload or another caller may have replaced the entry; registering again is cheap and idempotent.
        if(manager.getTexture(LOCATION,null)!=texture){manager.register(LOCATION,texture);uploaded=null;}
        int[] pixels=pixels(mc.level==null?null:JEDRenderController.policy(mc.level));
        if(java.util.Arrays.equals(pixels,uploaded))return;
        var image=texture.getPixels();if(image==null)return;
        for(int x=0;x<WIDTH;x++)image.setPixelRGBA(x,0,abgr(pixels[x]));
        texture.upload();uploaded=pixels;
    }
    /** ARGB per pixel; pure so the layout can be checked without a client. */
    static int[] pixels(JEDRenderDefinition p){
        int[] out=new int[WIDTH];
        out[MARKER]=argb('J'<<16|'E'<<8|'D',255);out[LAYOUT]=argb(VERSION<<16,255);
        if(p==null)return out;
        var sky=p.sky();
        out[SUN]=color(sky.sun().color());out[MOON]=color(sky.moon().color());out[SKY]=color(sky.color());
        out[FOG]=color(p.fog().color());out[CLOUDS]=color(p.clouds().color());
        int flags=0;
        if(!sky.stars())flags|=STARS_HIDDEN;
        if(!sky.sun().visible())flags|=SUN_HIDDEN;
        if(!sky.moon().visible())flags|=MOON_HIDDEN;
        if(Boolean.FALSE.equals(p.clouds().visible()))flags|=CLOUDS_HIDDEN;
        if("sphere".equals(p.fog().shape()))flags|=FOG_SPHERE;
        if("cylinder".equals(p.fog().shape()))flags|=FOG_CYLINDER;
        if(sky.mode()==JEDRenderDefinition.SkyMode.NONE)flags|=SKY_NONE;
        if(sky.mode()==JEDRenderDefinition.SkyMode.END)flags|=SKY_END;
        var water=p.biomeColors().get("water");if(water!=null&&!water.isEmpty())flags|=WATER_COLORS;
        out[FLAGS]=argb((flags&0xff)<<16|(flags>>8&0xff)<<8,255);
        if(sky.sun().scale()!=1)out[SUN_SCALE]=unsigned(sky.sun().scale()*256);
        if(sky.moon().scale()!=1)out[MOON_SCALE]=unsigned(sky.moon().scale()*256);
        if(p.clouds().height()!=null)out[CLOUD_HEIGHT]=unsigned(p.clouds().height()+32768);
        if(p.fog().near()!=null)out[FOG_NEAR]=unsigned(p.fog().near());
        if(p.fog().far()!=null)out[FOG_FAR]=unsigned(p.fog().far());
        if(sky.brightness()!=null)out[BRIGHTNESS]=unsigned(sky.brightness()*65535);
        return out;
    }
    private static int color(JEDRenderDefinition.Color c){return c==null?0:argb(c.rgb(),255);}
    /** A 16-bit value in red (high byte) and green (low byte), marked as set. */
    private static int unsigned(double value){int v=(int)Math.round(Math.max(0,Math.min(65535,value)));return argb(v<<8,255);}
    private static int argb(int rgb,int alpha){return alpha<<24|rgb&0xffffff;}
    /** NativeImage stores pixels as ABGR. */
    private static int abgr(int argb){return argb&0xff000000|(argb&0xff)<<16|argb&0xff00|argb>>16&0xff;}
    private JEDShaderPackColors(){}
}
