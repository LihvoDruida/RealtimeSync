package com.realtime.common;

import net.minecraft.server.MinecraftServer;

public final class RealtimeServerState {
    public static final long UNKNOWN_TICK = Long.MIN_VALUE;

    private RealtimeServerState() {
    }

    public static long tickCount(MinecraftServer server) {
        return server.getTickCount();
    }
}
