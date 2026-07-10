package com.realtime.common;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** Registers the operator-only diagnostic command directly on the active server dispatcher. */
public final class RealtimeStatusCommand {
    private static final CommandPermissionAccess PERMISSIONS = new ProfileCommandPermissionAccess();

    private RealtimeStatusCommand() {
    }

    public static String permissionAdapterName() {
        return PERMISSIONS.adapterName();
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            RealtimeController controller,
            RealtimeLog logger
    ) {
        try {
            dispatcher.register(
                    Commands.literal("realtimesync")
                            .requires(PERMISSIONS::canUse)
                            .then(Commands.literal("status")
                                    .executes(context -> {
                                        MinecraftServer server = context.getSource().getServer();
                                        for (String line : controller.statusLines(server)) {
                                            context.getSource().sendSuccess(() -> Component.literal(line), false);
                                        }
                                        return 1;
                                    }))
            );
        } catch (RuntimeException exception) {
            logger.warn("Could not register /realtimesync status using adapter {}: {}", PERMISSIONS.adapterName(), exception.toString());
        }
    }
}
