package justepicdimensions.showcase;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;

/** Identifies guide content for its reader links. Inventory retention uses only configured item IDs. */
public final class JEDShowcaseGuide {
    public static final String MARKER="justepicdimensions:showcase_guide";
    public static boolean guide(ItemStack stack){
        if(!(stack.getItem() instanceof WrittenBookItem))return false;
        var content=stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return content!=null&&content.getPages(false).stream().anyMatch(page->page.visit((style,text)->MARKER.equals(style.getInsertion())?Optional.of(true):Optional.empty(),net.minecraft.network.chat.Style.EMPTY).orElse(false));
    }
    private JEDShowcaseGuide(){}
}
