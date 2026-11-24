package justepicdimensions.admin;

import com.mojang.logging.LogUtils;
import justepicdimensions.JEDText;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Replies for commands that finish after they return. RCON only collects messages sent while a command runs, so console
 * and RCON senders are told up front that the result goes to the server log, and their results are logged. Failures of
 * administrative operations are logged whoever sent them. Every reply is sent on the server thread. */
public final class JEDCommandResults {
    /** Console, RCON, command blocks and functions: senders that cannot see a later chat message. */
    public static boolean console(CommandSourceStack source) { return !(source.getEntity() instanceof ServerPlayer); }
    /** The immediate reply of a background operation. */
    public static void started(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> console(source) ? Component.empty().append(message).append(" ").append(JEDText.tr("command.result_in_log")) : message, false);
    }
    /** For operations that previously gave no immediate reply: only console senders are told where the result goes. */
    public static void startedForConsole(CommandSourceStack source, String operation) {
        if (console(source)) source.sendSuccess(() -> JEDText.tr("command.started_in_log", operation), false);
    }
    public static void success(CommandSourceStack source, String operation, Component message, boolean broadcast) {
        onServer(source, () -> {
            source.sendSuccess(() -> message, broadcast);
            // A broadcast already reaches the log through the native admin-command echo.
            if (console(source) && !broadcast) LogUtils.getLogger().info("JED {}: {}", operation, message.getString());
        });
    }
    /** {@code always} logs the failure even for players: true for administrative operations, false for routine travel. */
    public static void failure(CommandSourceStack source, String operation, Component message, boolean always) {
        onServer(source, () -> {
            source.sendFailure(message);
            if (always || console(source)) LogUtils.getLogger().warn("JED {} failed (requested by {}): {}", operation, source.getTextName(), message.getString());
        });
    }
    private static void onServer(CommandSourceStack source, Runnable action) {
        var server = source.getServer(); if (server.isSameThread()) action.run(); else server.execute(action);
    }
    private JEDCommandResults() {}
}
