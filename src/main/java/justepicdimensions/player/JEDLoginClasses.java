package justepicdimensions.player;

/**
 * Resolve classes observed on the first native login during mod startup.
 * Loading is deliberately separate from initialization: no inventory adapters,
 * players, saved data, optional mod APIs or gameplay callbacks are constructed here.
 */
public final class JEDLoginClasses {
    private static final String[] TYPES = {
        "justepicdimensions.spawn.JEDPlayerStateTracker",
        "justepicdimensions.spawn.JEDPlayerStateTracker$Player",
        "justepicdimensions.player.JEDPlayerInventoryState",
        "justepicdimensions.dimension.JEDDimensionSync$Expected",
        "justepicdimensions.dimension.JEDPlayerReadiness",
        "justepicdimensions.dimension.JEDPlayerReadiness$State",
        "justepicdimensions.player.JEDInventoryGroupManager",
        "justepicdimensions.player.JEDInventoryAdapterRegistry",
        "justepicdimensions.player.JEDInventoryAdapter",
        "justepicdimensions.player.JEDVanillaInventoryAdapter",
        "justepicdimensions.player.JEDInventoryAdapter$Binding",
        "justepicdimensions.player.JEDAccessoryRuntime",
        "justepicdimensions.player.JEDLoadoutDefinition$Address",
        "justepicdimensions.player.JEDVanillaInventoryAdapter$1",
        "justepicdimensions.player.JEDInventoryCodec",
        "justepicdimensions.player.JEDInventoryGroupManager$Prepared",
        // Pinned native/NeoForge login dependencies observed in the same JFR windows.
        // Keep this explicit: do not scan mods or initialize these classes to warm them.
        "com.google.common.net.InetAddresses",
        "it.unimi.dsi.fastutil.objects.Object2IntFunction",
        "it.unimi.dsi.fastutil.objects.Object2IntMap$Entry",
        "it.unimi.dsi.fastutil.objects.Object2IntMaps$1",
        "it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap$FastEntryIterator",
        "it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap$KeyIterator",
        "it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap$KeySet",
        "net.minecraft.commands.arguments.EntityArgument$Info$Template",
        "net.minecraft.commands.synchronization.brigadier.IntegerArgumentInfo$Template",
        "net.minecraft.commands.synchronization.brigadier.StringArgumentSerializer$Template",
        "net.minecraft.network.chat.RemoteChatSession",
        "net.minecraft.network.chat.RemoteChatSession$Data",
        "net.minecraft.network.chat.SignedMessageValidator",
        "net.minecraft.network.protocol.game.ClientboundCommandsPacket$ArgumentNodeStub",
        "net.minecraft.network.protocol.game.ClientboundCommandsPacket$Entry",
        "net.minecraft.network.protocol.game.ClientboundCommandsPacket$LiteralNodeStub",
        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action$Reader",
        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action$Writer",
        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry",
        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$EntryBuilder",
        "net.minecraft.network.protocol.game.CommonPlayerSpawnInfo",
        "net.minecraft.Optionull",
        "net.minecraft.server.level.ChunkTrackingView$Positioned",
        "net.minecraft.world.entity.decoration.ArmorStand$1",
        "net.minecraft.world.entity.LivingEntity$1",
        "net.minecraft.world.entity.RelativeMovement",
        "net.neoforged.neoforge.common.UsernameCache$SaveThread",
        "net.neoforged.neoforge.entity.IEntityWithComplexSpawn",
        "net.neoforged.neoforge.network.bundle.BundlePacketUtils",
        "net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor",
        "net.neoforged.neoforge.network.PacketDistributor",
        "net.neoforged.neoforge.server.command.CommandHelper",
        "net.neoforged.neoforge.server.command.EnumArgument$Info$Template"
    };
    public record Preparation(int classes, long elapsedNanos) {}
    private static volatile Preparation prepared;

    public static synchronized Preparation prepare() {
        if (prepared != null) return prepared;
        long start = System.nanoTime();
        ClassLoader loader = JEDLoginClasses.class.getClassLoader();
        for (String type : TYPES) {
            try {
                Class.forName(type, false, loader);
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("JED login class is missing: " + type, error);
            }
        }
        prepared = new Preparation(TYPES.length, System.nanoTime() - start);
        return prepared;
    }

    public static Preparation preparation() { return prepared; }
    private JEDLoginClasses() {}
}
