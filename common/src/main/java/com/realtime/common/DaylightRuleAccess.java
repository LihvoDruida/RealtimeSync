package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Compile-time adapter selected by the active Minecraft build profile. */
public interface DaylightRuleAccess {
    boolean isTimeAdvancing(ServerLevel level);

    void setTimeAdvancing(ServerLevel level, MinecraftServer server, boolean advancing);

    String adapterName();
}
