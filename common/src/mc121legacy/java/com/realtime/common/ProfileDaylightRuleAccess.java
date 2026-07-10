package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/** Minecraft 1.21-1.21.10 GameRules adapter. */
public final class ProfileDaylightRuleAccess implements DaylightRuleAccess {
    @Override
    public boolean isTimeAdvancing(ServerLevel level) {
        return level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    @Override
    public void setTimeAdvancing(ServerLevel level, MinecraftServer server, boolean advancing) {
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(advancing, server);
    }

    @Override
    public String adapterName() {
        return "legacy-gamerules-1.21-1.21.10";
    }
}
