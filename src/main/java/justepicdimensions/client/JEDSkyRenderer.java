package justepicdimensions.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
import justepicdimensions.render.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/** Use native celestial geometry, moon-phase UVs and GPU buffers; change only selected inputs. */
public final class JEDSkyRenderer {
    private static final ByteBufferBuilder DARKEN=new ByteBufferBuilder(256);
    private static final MethodHandle SHADER_PACK=shaderPackQuery();
    private static ShaderInstance darkenShader;
    public static float size(float nativeSize,ClientLevel level,boolean moon){var p=JEDRenderController.policy(level);if(p==null)return nativeSize;var body=moon?p.sky().moon():p.sky().sun();return body.visible()?(float)(nativeSize*body.scale()):0;}
    public static void tint(ClientLevel level,boolean moon){var p=JEDRenderController.policy(level);if(p==null)return;var c=(moon?p.sky().moon():p.sky().sun()).color();RenderSystem.setShaderColor((float)c.red(),(float)c.green(),(float)c.blue(),RenderSystem.getShaderColor()[3]);}
    /**
     * Native sky bodies are added to the sky, so any tint washes out to white against a bright sky. A coloured body first
     * darkens the sky behind it by the texture's own brightness (black texture areas leave it untouched); the native draw
     * that follows then adds the tinted texture with the native blend. Uncoloured bodies keep the single native draw.
     * The darkening pass draws a copy of the native quad, so the native {@code BufferUploader.drawWithShader} call is
     * never replaced: other mods (Iris among them) wrap that call. While a shader pack is active the pass is skipped:
     * the pack's sky program recolours the copy too (Complementary turns any sun quad into bright HDR orange), and its
     * unclamped colour buffer then subtracts red and green from the sky, leaving a blue square where the sun should be.
     * The pass uses JED's {@code sky_darken} shader, which weights each texel by its alpha as the native draw does; the
     * native shader would darken semi-transparent edges fully and leave a dark rim around a soft texture.
     */
    public static void darken(MeshData mesh,ClientLevel level,boolean moon){
        var p=JEDRenderController.policy(level);var c=p==null?null:(moon?p.sky().moon():p.sky().sun()).color();
        if(c==null||c.rgb()==0xffffff||shaderPack())return;
        var state=mesh.drawState();if(state.format()!=DefaultVertexFormat.POSITION_TEX)return;
        var source=mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());int base=source.position(),stride=state.format().getVertexSize();
        var copy=new BufferBuilder(DARKEN,state.mode(),state.format());
        for(int i=0;i<state.vertexCount();i++){int o=base+i*stride;copy.addVertex(source.getFloat(o),source.getFloat(o+4),source.getFloat(o+8)).setUv(source.getFloat(o+12),source.getFloat(o+16));}
        var darkening=copy.build();if(darkening==null)return;
        float[] tint=RenderSystem.getShaderColor().clone();float alpha=tint[3];
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO,GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,GlStateManager.SourceFactor.ZERO,GlStateManager.DestFactor.ONE);
        var nativeShader=RenderSystem.getShader();var shader=darkenShader;if(shader!=null)RenderSystem.setShader(()->shader);
        RenderSystem.setShaderColor(alpha,alpha,alpha,1);BufferUploader.drawWithShader(darkening);
        if(shader!=null)RenderSystem.setShader(()->nativeShader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,GlStateManager.DestFactor.ONE,GlStateManager.SourceFactor.ONE,GlStateManager.DestFactor.ZERO);
        RenderSystem.setShaderColor(tint[0],tint[1],tint[2],alpha);
    }
    /** Set by the client's shader registration; null keeps the native shader (and its hard edges) for the pass. */
    static void darkenShader(ShaderInstance shader){darkenShader=shader;}
    /** Iris (or a fork with its v0 API) is drawing through a shader pack; false without Iris or when its API fails. */
    static boolean shaderPack(){
        if(SHADER_PACK==null)return false;
        try{return (boolean)SHADER_PACK.invokeExact();}catch(Throwable error){return false;}
    }
    private static MethodHandle shaderPackQuery(){
        for(String name:new String[]{"net.irisshaders.iris.api.v0.IrisApi","net.coderbot.iris.api.v0.IrisApi"}){
            try{
                var api=Class.forName(name);var instance=api.getMethod("getInstance").invoke(null);
                return MethodHandles.publicLookup().findVirtual(api,"isShaderPackInUse",MethodType.methodType(boolean.class)).bindTo(instance);
            }catch(ReflectiveOperationException|LinkageError|RuntimeException absent){}
        }
        return null;
    }
    public static ResourceLocation texture(ResourceLocation nativeTexture,ClientLevel level,boolean moon){var p=JEDRenderController.policy(level);if(p==null)return nativeTexture;var requested=(moon?p.sky().moon():p.sky().sun()).texture();return requested==null?nativeTexture:JEDClientRenderRuntime.texture(level.dimension().location(),requested,nativeTexture);}
    private JEDSkyRenderer(){}
}
