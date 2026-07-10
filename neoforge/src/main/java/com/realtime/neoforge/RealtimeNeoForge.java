package com.realtime.neoforge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeController;
import com.realtime.common.RealtimeLog;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        LOGGER.info("{} loaded for NeoForge. Config: {}", RealtimeConstants.MOD_NAME, controller.configPath().getFileName());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        controller.registerCommands(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        controller.onServerStarted(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        controller.onServerTick(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        controller.onServerStopped(event.getServer());
    }
}
