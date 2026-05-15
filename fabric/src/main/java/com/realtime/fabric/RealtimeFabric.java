package com.realtime.fabric;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeController;
import com.realtime.common.RealtimeLog;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public final class RealtimeFabric implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);

    private RealtimeController controller;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        controller = new RealtimeController(configDir, LOG);

        // Keep Fabric/Quilt entrypoints on the stable server tick event and let the common
        // controller perform startup/world daylight guards on the first server tick.
        ServerTickEvents.END_SERVER_TICK.register(controller::onServerTick);

        LOGGER.info("{} loaded for Fabric-compatible environments. Config: {}",
                RealtimeConstants.MOD_NAME,
                controller.configPath().toAbsolutePath());
    }
}
