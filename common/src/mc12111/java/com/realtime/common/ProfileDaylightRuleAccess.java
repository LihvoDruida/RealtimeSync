package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/** Minecraft 1.21.11 registry-backed GameRules adapter. */
public final class ProfileDaylightRuleAccess implements DaylightRuleAccess {
    @Override
    public boolean isTimeAdvancing(ServerLevel level) {
        return level.getGameRules().get(GameRules.ADVANCE_TIME);
    }

    @Override
    public void setTimeAdvancing(ServerLevel level, MinecraftServer server, boolean advancing) {
        level.getGameRules().set(GameRules.ADVANCE_TIME, advancing, server);
    }

    @Override
    public String adapterName() {
        return "registry-gamerules-1.21.11";
    }
}
