package com.realtime.common;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Minecraft 1.21.11 PermissionSet-backed command permission adapter. */
public final class ProfileCommandPermissionAccess implements CommandPermissionAccess {
    @Override
    public boolean canUse(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }

    @Override
    public String adapterName() {
        return "permission-set-gamemasters-1.21.11";
    }
}
