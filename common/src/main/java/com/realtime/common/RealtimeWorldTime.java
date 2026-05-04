package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Accesses day-time through the level data object instead of ServerLevel convenience methods.
 *
 * <p>Minecraft 26.1.x removed or renamed ServerLevel#getDayTime and ServerLevel#setDayTime in
 * the mapped API used by NeoForge/Fabric/Forge builds. The underlying level data accessors remain
 * the stable cross-version path and are remapped correctly by each loader toolchain.</p>
 */
public final class RealtimeWorldTime {
    private RealtimeWorldTime() {
    }

    public static long readOverworldTime(MinecraftServer server) {
        return readDayTime(server.overworld());
    }

    public static long readDayTime(ServerLevel level) {
        return level.getLevelData().getDayTime();
    }

    public static void setDayTime(ServerLevel level, long dayTime) {
        level.getLevelData().setDayTime(dayTime);
    }
}
