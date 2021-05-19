/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.platform.velocity;

import com.github.steveice10.packetlib.tcp.io.LocalChannelRemoteAddressWrapper;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import lombok.Getter;
import org.geysermc.common.PlatformType;
import org.geysermc.connector.GeyserConnector;
import org.geysermc.connector.bootstrap.GeyserBootstrap;
import org.geysermc.connector.configuration.GeyserConfiguration;
import org.geysermc.connector.dump.BootstrapDumpInfo;
import org.geysermc.connector.ping.GeyserLegacyPingPassthrough;
import org.geysermc.connector.ping.IGeyserPingPassthrough;
import org.geysermc.connector.utils.FileUtils;
import org.geysermc.connector.utils.LanguageUtils;
import org.geysermc.platform.velocity.command.GeyserVelocityCommandExecutor;
import org.geysermc.platform.velocity.command.GeyserVelocityCommandManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.Supplier;

@Plugin(id = "geyser", name = GeyserConnector.NAME + "-Velocity", version = GeyserConnector.VERSION, url = "https://geysermc.org", authors = "GeyserMC")
public class GeyserVelocityPlugin implements GeyserBootstrap {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxyServer;

    @Inject
    private CommandManager commandManager;

    private GeyserVelocityCommandManager geyserCommandManager;
    private GeyserVelocityConfiguration geyserConfig;
    private GeyserVelocityLogger geyserLogger;
    private IGeyserPingPassthrough geyserPingPassthrough;

    private GeyserConnector connector;

    @Getter
    private final Path configFolder = Paths.get("plugins/" + GeyserConnector.NAME + "-Velocity/");

    private SocketAddress serverSocketAddress;
    private ChannelFuture localChannel;

    @Override
    public void onEnable() {
        try {
            if (!configFolder.toFile().exists())
                //noinspection ResultOfMethodCallIgnored
                configFolder.toFile().mkdirs();
            File configFile = FileUtils.fileOrCopiedFromResource(configFolder.resolve("config.yml").toFile(), "config.yml", (x) -> x.replaceAll("generateduuid", UUID.randomUUID().toString()));
            this.geyserConfig = FileUtils.loadConfig(configFile, GeyserVelocityConfiguration.class);
        } catch (IOException ex) {
            logger.warn(LanguageUtils.getLocaleStringLog("geyser.config.failed"), ex);
            ex.printStackTrace();
        }

        InetSocketAddress javaAddr = proxyServer.getBoundAddress();

        // By default this should be localhost but may need to be changed in some circumstances
        if (this.geyserConfig.getRemote().getAddress().equalsIgnoreCase("auto")) {
            this.geyserConfig.setAutoconfiguredRemote(true);
            // Don't use localhost if not listening on all interfaces
            if (!javaAddr.getHostString().equals("0.0.0.0") && !javaAddr.getHostString().equals("")) {
                this.geyserConfig.getRemote().setAddress(javaAddr.getHostString());
            }
            geyserConfig.getRemote().setPort(javaAddr.getPort());
        }

        if (geyserConfig.getBedrock().isCloneRemotePort()) {
            geyserConfig.getBedrock().setPort(javaAddr.getPort());
        }

        this.geyserLogger = new GeyserVelocityLogger(logger, geyserConfig.isDebugMode());
        GeyserConfiguration.checkGeyserConfiguration(geyserConfig, geyserLogger);

        if (geyserConfig.getRemote().getAuthType().equals("floodgate") && !proxyServer.getPluginManager().getPlugin("floodgate").isPresent()) {
            geyserLogger.severe(LanguageUtils.getLocaleStringLog("geyser.bootstrap.floodgate.not_installed") + " " + LanguageUtils.getLocaleStringLog("geyser.bootstrap.floodgate.disabling"));
            return;
        } else if (geyserConfig.isAutoconfiguredRemote() && proxyServer.getPluginManager().getPlugin("floodgate").isPresent()) {
            // Floodgate installed means that the user wants Floodgate authentication
            geyserLogger.debug("Auto-setting to Floodgate authentication.");
            geyserConfig.getRemote().setAuthType("floodgate");
        }

        geyserConfig.loadFloodgate(this, proxyServer, configFolder.toFile());

        this.connector = GeyserConnector.start(PlatformType.VELOCITY, this);

        try {
            this.serverSocketAddress = initializeLocalChannel();
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.geyserCommandManager = new GeyserVelocityCommandManager(connector);
        this.commandManager.register("geyser", new GeyserVelocityCommandExecutor(connector));
        if (geyserConfig.isLegacyPingPassthrough()) {
            this.geyserPingPassthrough = GeyserLegacyPingPassthrough.init(connector);
        } else {
            this.geyserPingPassthrough = new GeyserVelocityPingPassthrough(proxyServer);
        }
    }

    @Override
    public void onDisable() {
        connector.shutdown();
        if (this.localChannel != null) {
            try {
                localChannel.channel().close().sync();
                localChannel = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public GeyserVelocityConfiguration getGeyserConfig() {
        return geyserConfig;
    }

    @Override
    public GeyserVelocityLogger getGeyserLogger() {
        return geyserLogger;
    }

    @Override
    public org.geysermc.connector.command.CommandManager getGeyserCommandManager() {
        return this.geyserCommandManager;
    }

    @Override
    public IGeyserPingPassthrough getGeyserPingPassthrough() {
        return geyserPingPassthrough;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        onEnable();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        onDisable();
    }

    @Override
    public BootstrapDumpInfo getDumpInfo() {
        return new GeyserVelocityDumpInfo(proxyServer);
    }

    @Nullable
    @Override
    public SocketAddress getSocketAddress() {
        return serverSocketAddress;
    }

    @SuppressWarnings("unchecked")
    private SocketAddress initializeLocalChannel() throws Exception {
        // <3 Velocity
        Field cm = proxyServer.getClass().getDeclaredField("cm");
        cm.setAccessible(true);
        Object connectionManager = cm.get(proxyServer);
        Class<?> connectionManagerClass = connectionManager.getClass();

        Supplier<ChannelInitializer<Channel>> serverChannelInitializerHolder = (Supplier<ChannelInitializer<Channel>>) connectionManagerClass
                .getMethod("getServerChannelInitializer")
                .invoke(connectionManager);
        ChannelInitializer<Channel> channelInitializer = serverChannelInitializerHolder.get();

        EventLoopGroup bossGroup = (EventLoopGroup) connectionManagerClass.getMethod("getBossGroup").invoke(connectionManager);

        Field workerGroupField = connectionManagerClass.getDeclaredField("workerGroup");
        workerGroupField.setAccessible(true);
        EventLoopGroup workerGroup = (EventLoopGroup) workerGroupField.get(connectionManager);

        // This method is what initializes the connection in Java Edition, after Netty is all set.
        Method initChannel = channelInitializer.getClass().getDeclaredMethod("initChannel", Channel.class);
        initChannel.setAccessible(true);

        ChannelFuture channelFuture = (new ServerBootstrap().channel(LocalServerChannel.class).childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                // Replace the LocalChannel with a wrapper that allows for flexibility of the remote address
                // (By default this is a LocalAddress; Minecraft largely expects an INetSocketAddress class)
                LocalChannelRemoteAddressWrapper wrapper = new LocalChannelRemoteAddressWrapper((LocalChannel) ch);
                wrapper.remoteAddress(new InetSocketAddress(0));
                initChannel.invoke(channelInitializer, wrapper);
            }
        }).group(bossGroup, workerGroup).localAddress(LocalAddress.ANY)).bind().syncUninterruptibly(); // Cannot be DefaultEventLoopGroup
        this.localChannel = channelFuture;

        return channelFuture.channel().localAddress();
    }
}
