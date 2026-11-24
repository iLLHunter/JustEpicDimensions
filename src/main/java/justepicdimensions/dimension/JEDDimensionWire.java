package justepicdimensions.dimension;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.nbt.*;

/** Protocol-four validation shared by network decoding and the client assembly. No native state is changed here. */
public final class JEDDimensionWire {
    /** Check wire length before NBT reads strings/arrays, then account the decoded tag allocations. */
    public static net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, CompoundTag> codec(int limit) {
        var nbt = net.minecraft.network.codec.ByteBufCodecs.compoundTagCodec(() -> NbtAccounter.create(limit));
        return new net.minecraft.network.codec.StreamCodec<>() {
            public CompoundTag decode(net.minecraft.network.RegistryFriendlyByteBuf input) {
                if (input.readableBytes() > limit) throw invalid("Oversized dimension payload");
                return nbt.decode(input);
            }
            public void encode(net.minecraft.network.RegistryFriendlyByteBuf output, CompoundTag data) {
                int start = output.writerIndex(); nbt.encode(output, data);
                if (output.writerIndex() - start > limit) throw invalid("Oversized dimension payload");
            }
        };
    }
    public record Header(String token, String digest, long revision, long policy) {}
    public record Complete(Header header, String json) {}
    public static Header header(CompoundTag data) {
        for (String name : List.of("token", "sha256")) require(data, name, Tag.TAG_STRING);
        for (String name : List.of("revision", "policy_revision")) require(data, name, Tag.TAG_LONG);
        String token = data.getString("token"), digest = data.getString("sha256");
        try { if (!UUID.fromString(token).toString().equals(token)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException error) { throw invalid("Invalid dimension snapshot token"); }
        if (digest.length() != 64 || !digest.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))
            throw invalid("Invalid dimension snapshot digest");
        long revision = data.getLong("revision"), policy = data.getLong("policy_revision");
        if (revision < 0 || policy < 1) throw invalid("Invalid dimension snapshot revision");
        return new Header(token, digest, revision, policy);
    }
    public static Header ack(CompoundTag data) {
        Header header = header(data);
        if (!data.getAllKeys().equals(Set.of("token", "sha256", "revision", "policy_revision"))) throw invalid("Unexpected dimension acknowledgment fields");
        return header;
    }
    private static final Set<String> FRAME_KEYS = Set.of("token", "sha256", "revision", "policy_revision", "index", "count", "size", "bytes");
    private static final Set<String> BARRIER_FRAME_KEYS = Set.of("token", "sha256", "revision", "policy_revision", "index", "count", "size", "bytes", "barrier");
    /** Absent means a barrier: protocol-three frames, and every publication after login, hold following traffic. */
    public static boolean barrier(CompoundTag data) { return !data.contains("barrier", Tag.TAG_BYTE) || data.getByte("barrier") != 0; }
    public static Header frame(CompoundTag data) {
        Header header = header(data);
        var keys = data.getAllKeys();
        if (!keys.equals(FRAME_KEYS) && !keys.equals(BARRIER_FRAME_KEYS)) throw invalid("Unexpected dimension snapshot fields");
        if (keys.contains("barrier")) { require(data, "barrier", Tag.TAG_BYTE); byte flag = data.getByte("barrier"); if (flag != 0 && flag != 1) throw invalid("Invalid dimension snapshot barrier"); }
        for (String name : List.of("index", "count", "size")) require(data, name, Tag.TAG_INT);
        require(data, "bytes", Tag.TAG_BYTE_ARRAY);
        int size = data.getInt("size"), count = data.getInt("count"), index = data.getInt("index");
        if (size <= 0 || size > JEDDimensionSync.MAX_BYTES || count != (size + JEDDimensionSync.FRAME_BYTES - 1) / JEDDimensionSync.FRAME_BYTES
            || index < 0 || index >= count) throw invalid("Invalid dimension snapshot frame size or index");
        if (data.getByteArray("bytes").length != Math.min(JEDDimensionSync.FRAME_BYTES, size - index * JEDDimensionSync.FRAME_BYTES))
            throw invalid("Truncated dimension snapshot frame");
        return header;
    }
    private static void require(CompoundTag data, String name, int type) {
        if (data == null || !data.contains(name, type)) throw invalid("Missing or mistyped dimension snapshot field: " + name);
    }
    private static JEDDefinitionException invalid(String message) { return new JEDDefinitionException(message); }
    /** One ordered transfer per connection, allocated incrementally and discarded on any failure. */
    public static final class Assembly {
        private Header header;
        private ByteArrayOutputStream bytes;
        private int index, count, size;
        private boolean barrier;
        public Complete accept(CompoundTag data) {
            try {
                Header next = frame(data); int part = data.getInt("index");
                if (part == 0) {
                    if (bytes != null) throw invalid("Interrupted dimension snapshot transfer");
                    header = next; count = data.getInt("count"); size = data.getInt("size"); index = 0; barrier = barrier(data);
                    bytes = new ByteArrayOutputStream(Math.min(size, JEDDimensionSync.FRAME_BYTES));
                }
                if (bytes == null || !header.equals(next) || part != index || count != data.getInt("count") || size != data.getInt("size") || barrier != barrier(data))
                    throw invalid("Out-of-order dimension snapshot frame");
                bytes.writeBytes(data.getByteArray("bytes")); index++;
                if (index < count) return null;
                byte[] complete = bytes.toByteArray();
                if (!JEDDimensionSync.digest(complete).equals(header.digest())) throw invalid("Dimension snapshot digest mismatch");
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(complete)).toString();
                Complete result = new Complete(header, json); clear(); return result;
            } catch (CharacterCodingException error) { clear(); throw invalid("Invalid dimension snapshot UTF-8"); }
            catch (RuntimeException | Error error) { clear(); throw error; }
        }
        public void clear() { header = null; bytes = null; index = count = size = 0; }
    }
    private JEDDimensionWire() {}
}
