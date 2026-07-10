package com.realtime.common;

import net.minecraft.server.level.ServerLevel;

/** Minecraft 1.21-1.21.10 ResourceKey adapter. */
public final class ProfileDimensionIdAccess implements DimensionIdAccess {
    @Override
    public String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    @Override
    public String adapterName() {
        return "resource-key-location-1.21-1.21.10";
    }
}
