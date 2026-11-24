package justepicdimensions.player;

import com.google.gson.JsonElement;
import java.util.*;
import justepicdimensions.definition.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.ItemStack;

/** Explicit resource IDs and #tags; tag contents are evaluated against the current native bindings. */
public record JEDPlayerSelector(ResourceLocation id, boolean tag) {
    public static List<JEDPlayerSelector> decode(JsonElement value, String source, boolean effect) {
        if (value == null || !value.isJsonArray()) throw new JEDDefinitionException(source + ": expected an ID/tag array");
        var result = new LinkedHashSet<JEDPlayerSelector>();
        for (var entry : value.getAsJsonArray()) {
            String text = JEDJson.string(entry, source);
            boolean tag = text.startsWith("#");
            var id = JEDDimensionDefinition.id(tag ? text.substring(1) : text, source);
            if (!tag && !(effect ? BuiltInRegistries.MOB_EFFECT.containsKey(id) : BuiltInRegistries.ITEM.containsKey(id)))
                throw new JEDDefinitionException(source + ": unknown " + (effect ? "effect " : "item ") + id);
            if (!result.add(new JEDPlayerSelector(id, tag))) throw new JEDDefinitionException(source + ": duplicate selector " + text);
        }
        return List.copyOf(result);
    }
    public boolean item(ItemStack stack) {
        return tag ? stack.is(TagKey.create(Registries.ITEM, id)) : stack.is(BuiltInRegistries.ITEM.get(id));
    }
    public boolean effect(Holder<MobEffect> effect) {
        return tag ? effect.is(TagKey.create(Registries.MOB_EFFECT, id)) : effect.is(ResourceKey.create(Registries.MOB_EFFECT, id));
    }
}
