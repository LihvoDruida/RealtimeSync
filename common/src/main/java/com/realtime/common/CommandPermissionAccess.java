package com.realtime.common;

import net.minecraft.commands.CommandSourceStack;

/** Build-profile-specific operator permission check for RealtimeSync commands. */
public interface CommandPermissionAccess {
    boolean canUse(CommandSourceStack source);

    String adapterName();
}
