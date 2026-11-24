package justepicdimensions.client;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import justepicdimensions.JustEpicDimensions;
import justepicdimensions.JEDText;
import justepicdimensions.definition.*;
import justepicdimensions.dimension.*;
import justepicdimensions.lighting.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Append native client registry data in-place without replacing existing registry/level objects. */
@EventBusSubscriber(modid = JustEpicDimensions.ID, value = Dist.CLIENT)
public final class JEDClientDimensionRuntime {
    private static final JEDDimensionWire.Assembly assembly = new JEDDimensionWire.Assembly();
    private static net.minecraft.client.multiplayer.ClientPacketListener owner;
    private static long revision = -1;
    static { JEDDimensionSync.clientReceiver(JEDClientDimensionRuntime::receive); }
    public static void receive(JEDDimensionSync.Frame frame, IPayloadContext context,java.util.function.Consumer<JEDDimensionSync.Ack> applied) {
        var connection = Minecraft.getInstance().getConnection();
        // Work queued by a retired connection must never mutate a newly joined server's registries.
        if (connection == null || connection != context.listener() || !justepicdimensions.network.JEDPayloads.current(context)) return;
        if (owner != connection) { assembly.clear(); revision = -1; owner = connection; }
        try {
            var complete = assembly.accept(frame.data());
            if (complete == null) return;
            var header = complete.header();
            var body = JEDJson.object(JEDJson.read(new StringReader(complete.json()), "server dimension snapshot"), "dimension snapshot");
            body.addProperty("revision", header.revision()); body.addProperty("policy_revision", header.policy());
            apply(body, connection);
            var ack = new CompoundTag(); ack.putString("token", header.token()); ack.putString("sha256", header.digest());
            ack.putLong("revision", header.revision()); ack.putLong("policy_revision", header.policy());
            applied.accept(new JEDDimensionSync.Ack(ack));
        } catch (Throwable error) {
            assembly.clear();
            com.mojang.logging.LogUtils.getLogger().error("JED dimension synchronization failed", error);
            justepicdimensions.network.JEDPayloads.disconnect(context, JEDText.tr("connection.sync",JEDText.error(error)));
        }
    }
    private static void apply(JsonObject body, net.minecraft.client.multiplayer.ClientPacketListener connection) {
        var access = connection.registryAccess();
        long nextRevision = body.get("revision").getAsLong();
        if (nextRevision < revision) throw new JEDDefinitionException("Obsolete dimension registry snapshot");
        var additions = new TreeMap<ResourceLocation, JEDSourceDocument>(); var types = new ArrayList<RegistryDataLoader.RegistryData<?>>();
        for (var entry : body.getAsJsonObject("registries").entrySet()) {
            var registryId = ResourceLocation.parse(entry.getKey());
            var type = RegistryDataLoader.SYNCHRONIZED_REGISTRIES.stream().filter(t -> t.key().location().equals(registryId)).findFirst()
                .orElseThrow(() -> new JEDDefinitionException("Unknown native network registry " + registryId)); types.add(type);
            var registry = access.registryOrThrow(type.key()); var block = entry.getValue().getAsJsonObject(); var layout = block.getAsJsonArray("layout");
            if (registry.size() > layout.size()) throw new JEDDefinitionException("Server registry layout shrank: " + registryId);
            for (int i = 0; i < registry.size(); i++) if (!registry.getHolder(i).orElseThrow().key().location().toString().equals(layout.get(i).getAsString()))
                throw new JEDDefinitionException("Existing native packet ID differs: " + registryId + " / " + i);
            String directory = Registries.elementsDirPath(type.key());
            for (int i = registry.size(); i < layout.size(); i++) {
                var id = ResourceLocation.parse(layout.get(i).getAsString()); var value = block.getAsJsonObject("entries").get(id.toString());
                if (value == null) throw new JEDDefinitionException("Missing runtime registry data: " + id);
                additions.put(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), directory + "/" + id.getPath() + ".json"), new JEDSourceDocument(value, "server native registry snapshot"));
            }
        }
        // Reject the complete envelope before publishing any native registry additions.
        var active = JEDDimensionSync.activeKeys(body.getAsJsonArray("dimensions"));
        if (JEDClientLightRuntime.policyRevision() < body.get("policy_revision").getAsLong()) throw new JEDDefinitionException("Lighting policy did not become ready");
        var transaction = new JEDRegistryTransaction(access, additions, types);
        transaction.reorder(body.getAsJsonObject("registries")); transaction.publish();
        connection.levels().retainAll(active); connection.levels().addAll(active);
        revision = nextRevision;
        JEDClientRenderRuntime.registriesChanged();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { assembly.clear(); owner = null; revision = -1; }
    public static long revision() { return revision; }
    private JEDClientDimensionRuntime() {}
}
