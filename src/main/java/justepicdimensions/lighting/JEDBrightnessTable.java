package justepicdimensions.lighting;

import com.google.gson.*;
import java.util.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.nbt.*;

/** Immutable native-indexed table. Values deliberately retain upstream's unclamped semantics. */
public final class JEDBrightnessTable {
    private final float[] values;
    public JEDBrightnessTable(float[] values) {
        if (values.length != 16) throw new JEDDefinitionException("brightness_table must contain exactly 16 numbers");
        this.values = values.clone();
        for (float value : values) if (!Float.isFinite(value)) throw new JEDDefinitionException("brightness_table values must be finite floats");
    }
    public float value(int index) { return values[index]; }
    public static JEDBrightnessTable decode(JsonElement json) {
        if (!json.isJsonArray() || json.getAsJsonArray().size() != 16) throw new JEDDefinitionException("brightness_table must contain exactly 16 numbers");
        float[] values = new float[16];
        for (int i=0;i<16;i++) {
            var entry = json.getAsJsonArray().get(i);
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) throw new JEDDefinitionException("brightness_table["+i+"] must be a number");
            values[i] = entry.getAsFloat();
        }
        return new JEDBrightnessTable(values);
    }
    public ListTag toTag() { var list = new ListTag(); for (float value : values) list.add(FloatTag.valueOf(value)); return list; }
    public static JEDBrightnessTable fromTag(Tag tag) {
        if (!(tag instanceof ListTag list) || list.size()!=16 || list.getElementType()!=Tag.TAG_FLOAT) throw new JEDDefinitionException("Invalid network brightness_table");
        float[] values = new float[16]; for (int i=0;i<16;i++) values[i]=list.getFloat(i); return new JEDBrightnessTable(values);
    }
    @Override public boolean equals(Object other) { return other instanceof JEDBrightnessTable table && Arrays.equals(values,table.values); }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}
