package com.realtime.common;

import net.minecraft.commands.CommandSourceStack;

/** Minecraft 1.21-1.21.10 numeric command permission adapter. */
public final class ProfileCommandPermissionAccess implements CommandPermissionAccess {
    @Override
    public boolean canUse(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    @Override
    public String adapterName() {
        return "command-permission-level-2-1.21-1.21.10";
    }
}
