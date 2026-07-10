package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Stable server-level access points shared by loader entrypoints. */
public final class RealtimeWorldAccess {
    private RealtimeWorldAccess() {
    }

    public static MinecraftServer server(ServerLevel level) {
        return level.getServer();
    }

    public static Object gameRulesIdentity(ServerLevel level) {
        return level.getGameRules();
    }
}
