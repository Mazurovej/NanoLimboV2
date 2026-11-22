/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.ResourceLeakDetector;
import lombok.Getter;
import ua.nanit.limbo.configuration.LimboConfig;
import ua.nanit.limbo.connection.ClientChannelInitializer;
import ua.nanit.limbo.connection.ClientConnection;
import ua.nanit.limbo.connection.PacketHandler;
import ua.nanit.limbo.connection.PacketSnapshots;
import ua.nanit.limbo.server.data.SchematicConfig;
import ua.nanit.limbo.world.DimensionRegistry;
import ua.nanit.limbo.world.DimensionType;
import ua.nanit.limbo.world.schematic.SchematicLoader;
import ua.nanit.limbo.world.schematic.SchematicWorld;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Getter
public final class LimboServer {

    private LimboConfig config;
    private PacketHandler packetHandler;
    private Connections connections;
    private DimensionRegistry dimensionRegistry;
    private SchematicWorld schematicWorld;
    private ScheduledFuture<?> keepAliveTask;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private CommandManager commandManager;

    public void start() throws Exception {
        config = new LimboConfig(Paths.get("./"));
        config.load();

        Log.setLevel(config.getDebugLevel());
        Log.info("Starting server...");

        if (System.getProperty("io.netty.leakDetectionLevel") == null && System.getProperty("io.netty.leakDetection.level") == null) {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        }

        packetHandler = new PacketHandler(this);
        dimensionRegistry = new DimensionRegistry(this);
        dimensionRegistry.load();
        loadSchematic();
        connections = new Connections(config);

        PacketSnapshots.initPackets(this);

        startBootstrap();

        keepAliveTask = workerGroup.scheduleAtFixedRate(this::broadcastKeepAlive, 0L, 5L, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "NanoLimbo shutdown thread"));

        Log.info("Server started on %s", config.getAddress());

        commandManager = new CommandManager();
        commandManager.registerAll(this);
        commandManager.start();

        System.gc();
    }

    private void loadSchematic() {
        SchematicConfig schemConfig = config.getSchematic();
        if (schemConfig == null || !schemConfig.isEnabled()) {
            return;
        }

        Path schemPath = config.getRoot().resolve(schemConfig.getFile());
        if (!Files.exists(schemPath)) {
            if ("spawn.schem".equals(schemConfig.getFile())) {
                try (InputStream in = getClass().getResourceAsStream("/spawn.schem")) {
                    if (in != null) {
                        if (schemPath.getParent() != null) {
                            Files.createDirectories(schemPath.getParent());
                        }
                        Files.copy(in, schemPath);
                        Log.info("Extracted default schematic to %s", schemPath);
                    }
                } catch (IOException e) {
                    Log.error("Failed to extract default spawn.schem", e);
                    return;
                }
            } else {
                Log.warning("Schematic file %s not found; players will spawn in the void.", schemPath);
                return;
            }
        }

        try {
            boolean hasSkyLight = config.getDimensionType() == DimensionType.OVERWORLD;
            schematicWorld = SchematicLoader.load(
                    schemPath,
                    schemConfig.getOffsetX(),
                    schemConfig.getOffsetY(),
                    schemConfig.getOffsetZ(),
                    hasSkyLight
            );
            Log.info("Loaded schematic %s (%d chunks)", schemConfig.getFile(), schematicWorld.getChunks().size());
        } catch (Exception e) {
            Log.error("Failed to load schematic " + schemPath + "; falling back to void.", e);
            schematicWorld = null;
        }
    }

    private void startBootstrap() {
        TransportType transportType = config.getTransportType();
        if (!transportType.isAvailable()) {
            Log.debug("Transport type " + transportType.name() + " is not available! Using NIO.");
            transportType = TransportType.NIO;
        }

        Log.debug("Using " + transportType.name() + " transport type");

        ChannelFactory<? extends ServerChannel> channelFactory = transportType.getChannelFactory();
        IoHandlerFactory ioHandlerFactory = transportType.getIoHandlerFactory();

        bossGroup = new MultiThreadIoEventLoopGroup(config.getBossGroupSize(), ioHandlerFactory);
        workerGroup = new MultiThreadIoEventLoopGroup(config.getWorkerGroupSize(), ioHandlerFactory);

        new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channelFactory(channelFactory)
                .childHandler(new ClientChannelInitializer(this))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .localAddress(config.getAddress())
                .bind()
                .syncUninterruptibly();
    }

    private void broadcastKeepAlive() {
        connections.getAllConnections().forEach(ClientConnection::sendKeepAlive);
    }

    private void stop() {
        Log.info("Stopping server...");

        if (keepAliveTask != null) {
            keepAliveTask.cancel(true);
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        Log.info("Server stopped, Goodbye!");
    }
}
