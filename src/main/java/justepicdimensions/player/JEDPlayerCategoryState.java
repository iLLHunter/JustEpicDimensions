package justepicdimensions.player;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/** Native-value snapshots for the four numeric categories. Effect snapshots have their own clock-aware codec. */
public final class JEDPlayerCategoryState {
    public static CompoundTag capture(ServerPlayer player, JEDPlayerCategory category) {
        var tag = new CompoundTag();
        switch (category) {
            case XP -> {
                tag.putInt("level", player.experienceLevel); tag.putFloat("progress", player.experienceProgress);
                tag.putInt("total", player.totalExperience);
            }
            case HEALTH -> { tag.putFloat("health", player.getHealth()); tag.putFloat("maximum", player.getMaxHealth()); }
            case ABSORPTION -> tag.putFloat("amount", player.getAbsorptionAmount());
            case FOOD -> {
                var food = player.getFoodData(); tag.putInt("hunger", food.getFoodLevel());
                tag.putFloat("saturation", food.getSaturationLevel()); tag.putFloat("exhaustion", food.getExhaustionLevel());
            }
            default -> throw new IllegalArgumentException("Effects require clock-aware snapshots");
        }
        validate(category, tag);
        return tag;
    }

    public static CompoundTag initial(ServerPlayer player, JEDPlayerGroupDefinition definition) {
        var initial = definition.initial().getAsJsonObject();
        var tag = new CompoundTag();
        switch (definition.category()) {
            case XP -> {
                int level = integer(initial, "level", 0);
                float progress = number(initial, "progress", 0);
                tag.putInt("level", level); tag.putFloat("progress", progress);
                tag.putInt("total", integer(initial, "total", initialExperience(level, progress)));
            }
            case HEALTH -> {
                float maximum = player.getMaxHealth();
                float health = initial.has("hearts") ? number(initial, "hearts", 0) * 2
                        : maximum * number(initial, "percentage", 1);
                tag.putFloat("health", Math.min(health, maximum)); tag.putFloat("maximum", maximum);
            }
            case ABSORPTION -> tag.putFloat("amount", number(initial, "hearts", 0) * 2);
            case FOOD -> {
                var nativeFresh = new net.minecraft.world.food.FoodData();
                tag.putInt("hunger", integer(initial, "hunger", nativeFresh.getFoodLevel()));
                tag.putFloat("saturation", number(initial, "saturation", nativeFresh.getSaturationLevel()));
                tag.putFloat("exhaustion", number(initial, "exhaustion", nativeFresh.getExhaustionLevel()));
            }
            default -> throw new IllegalArgumentException("Effects require clock-aware snapshots");
        }
        validate(definition.category(), tag);
        return tag;
    }

    public static void restore(ServerPlayer player, JEDPlayerGroupDefinition definition, CompoundTag tag) {
        validate(definition.category(), tag);
        switch (definition.category()) {
            case XP -> {
                player.experienceLevel = tag.getInt("level"); player.experienceProgress = tag.getFloat("progress");
                player.totalExperience = tag.getInt("total");
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetExperiencePacket(
                        player.experienceProgress, player.totalExperience, player.experienceLevel));
            }
            case HEALTH -> {
                float value = tag.getFloat("health");
                if (definition.health().restore() == JEDPlayerGroupDefinition.Restore.PERCENTAGE)
                    value = value / tag.getFloat("maximum") * player.getMaxHealth();
                player.setHealth(Math.min(value, player.getMaxHealth()));
            }
            case ABSORPTION -> {
                float value = tag.getFloat("amount");
                JEDPlayerHealthLimits.absorption(player, value); player.setAbsorptionAmount(value);
            }
            case FOOD -> {
                var food = player.getFoodData(); food.setFoodLevel(tag.getInt("hunger"));
                food.setSaturation(tag.getFloat("saturation")); food.setExhaustion(tag.getFloat("exhaustion"));
            }
            default -> throw new IllegalArgumentException("Effects require clock-aware restoration");
        }
    }

    public static void validate(JEDPlayerCategory category, CompoundTag tag) {
        switch (category) {
            case XP -> {
                integer(tag, "level"); integer(tag, "total"); floatValue(tag, "progress", true);
                if (tag.getFloat("progress") > 1) throw invalid(category);
            }
            case HEALTH -> { floatValue(tag, "health", true); floatValue(tag, "maximum", false); }
            case ABSORPTION -> floatValue(tag, "amount", true);
            case FOOD -> { integer(tag, "hunger"); floatValue(tag, "saturation", true); floatValue(tag, "exhaustion", true); }
            default -> throw new IllegalArgumentException("Effects require clock-aware validation");
        }
    }

    private static void integer(CompoundTag tag, String key) {
        if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_INT) || tag.getInt(key) < 0)
            throw new IllegalStateException("Invalid saved player-state integer '" + key + "'; data retained");
    }
    private static void floatValue(CompoundTag tag, String key, boolean zero) {
        float value = tag.getFloat(key);
        if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_FLOAT) || !Float.isFinite(value) || (zero ? value < 0 : value <= 0))
            throw new IllegalStateException("Invalid saved player-state value '" + key + "'; data retained");
    }
    private static IllegalStateException invalid(JEDPlayerCategory category) {
        return new IllegalStateException("Invalid saved " + category.key() + " state; data retained");
    }
    private static int integer(JsonObject object, String key, int fallback) { return object.has(key) ? object.get(key).getAsInt() : fallback; }
    private static float number(JsonObject object, String key, float fallback) { return object.has(key) ? object.get(key).getAsFloat() : fallback; }
    private static int initialExperience(int level, float progress) {
        double total = level <= 16 ? (double) level * level + 6d * level
                : level <= 31 ? 2.5 * level * level - 40.5 * level + 360
                : 4.5 * level * level - 162.5 * level + 2220;
        double next = level >= 30 ? 112d + (level - 30d) * 9 : level >= 15 ? 37d + (level - 15d) * 5 : 7d + level * 2d;
        return (int) Math.min(Integer.MAX_VALUE, total + Math.floor(next * progress));
    }
    private JEDPlayerCategoryState() {}
}
