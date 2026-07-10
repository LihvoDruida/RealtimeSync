package com.realtime.common;

import net.minecraft.server.level.ServerLevel;

/** Minecraft 1.21.11 ResourceKey adapter. */
public final class ProfileDimensionIdAccess implements DimensionIdAccess {
    @Override
    public String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    @Override
    public String adapterName() {
        return "resource-key-identifier-1.21.11";
    }
}
