package justepicdimensions.client;

import justepicdimensions.JustEpicDimensions;
import justepicdimensions.travel.JEDTravelSync;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid=JustEpicDimensions.ID,value=Dist.CLIENT)
public final class JEDClientTravel {
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){JEDTravelSync.clearClient();justepicdimensions.dimension.JEDDimensionNamesSync.clearClient();}
    private JEDClientTravel(){}
}
