package justepicdimensions.client;

import justepicdimensions.provider.*;
import justepicdimensions.lighting.JEDClientLightPolicy.Profile;
import net.minecraft.client.Minecraft;

/** Only the environmental fog predicate is overridden; native boss fog remains separate. */
public final class JEDClientProviderRuntime {
    public static boolean fog(boolean nativeValue){var minecraft=Minecraft.getInstance();var level=minecraft.level;if(level==null)return nativeValue;var p=JEDProviderController.policy(level);return p==null?nativeValue:p.fog(nativeValue,level,minecraft.gameRenderer.getMainCamera().getBlockPosition());}
    private JEDClientProviderRuntime(){}
}
