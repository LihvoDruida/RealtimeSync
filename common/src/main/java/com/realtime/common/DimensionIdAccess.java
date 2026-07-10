package com.realtime.common;

import net.minecraft.server.level.ServerLevel;

/** Build-profile-specific access to a level's namespaced ResourceKey identifier. */
public interface DimensionIdAccess {
    String dimensionId(ServerLevel level);

    String adapterName();
}
