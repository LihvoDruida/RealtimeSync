package com.realtime.neoforge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeController;
import com.realtime.common.RealtimeLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeNeoForge {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);

    private final RealtimeController controller;

    public RealtimeNeoForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        controller = new RealtimeController(configDir, LOG);

        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);

        LOGGER.info("{} loaded for NeoForge. Config: {}", RealtimeConstants.MOD_NAME, controller.configPath().toAbsolutePath());
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server != null) {
            controller.onWorldLoad(server, level);
        }
    }

    private void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // Run once per server tick instead of once per loaded dimension.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server != null) {
            controller.onServerTick(server);
        }
    }
}
