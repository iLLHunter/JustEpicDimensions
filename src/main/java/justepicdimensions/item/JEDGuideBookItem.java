package justepicdimensions.item;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;

/** A registered written book. Its retention is entirely the configurable global inventory policy. */
public final class JEDGuideBookItem extends WrittenBookItem {
    public JEDGuideBookItem(Item.Properties properties){super(properties.stacksTo(16).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true).component(DataComponents.WRITTEN_BOOK_CONTENT,content()));}
    private static WrittenBookContent content(){
        try(var input=Objects.requireNonNull(JEDGuideBookItem.class.getResourceAsStream("/assets/justepicdimensions/guide_book.json"));var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            return WrittenBookContent.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseReader(reader)).getOrThrow(message->new IllegalStateException("Invalid bundled JED guide: "+message));
        }catch(IOException error){throw new IllegalStateException("Cannot read bundled JED guide",error);}
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        var stack=player.getItemInHand(hand);
        if(player instanceof ServerPlayer server){
            if(WrittenBookItem.resolveBookComponents(stack,server.createCommandSourceStack(),server))server.containerMenu.broadcastChanges();
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(server,new JEDGuideBookOpen(hand));
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide());
    }
}
