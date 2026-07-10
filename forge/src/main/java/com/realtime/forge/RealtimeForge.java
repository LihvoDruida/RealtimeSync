package com.realtime.forge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeController;
import com.realtime.common.RealtimeLog;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeForge {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);

    private final RealtimeController controller;

    public RealtimeForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        controller = new RealtimeController(configDir, LOG);

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopped);

        LOGGER.info("{} loaded for Forge. Config: {}", RealtimeConstants.MOD_NAME, controller.configPath().getFileName());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        controller.registerCommands(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        controller.onServerStarted(event.getServer());
    }

    private void onServerTick(TickEvent.ServerTickEvent.Post event) {
        controller.onServerTick(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        controller.onServerStopped(event.getServer());
    }
}
