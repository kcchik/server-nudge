package com.kevin.servernudge;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "server-nudge",
        name = "ServerNudge",
        version = "1.0.0",
        description = "Starts the correct EC2 instance when a player tries to join a sleeping backend server."
)
public class ServerNudgePlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final Map<String, String[]> serverConfig = new HashMap<>();
    private final Map<String, Long> lastTriggered = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = TimeUnit.SECONDS.toMillis(20);

    @com.google.inject.Inject
    public ServerNudgePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        loadConfig();
        logger.info("ServerNudge loaded, managing {} server(s): {}", serverConfig.size(), serverConfig.keySet());
    }

    private void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            Path configPath = dataDirectory.resolve("config.properties");
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties.default")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
                logger.warn("No config found — wrote a template to {}. Fill it in and restart.", configPath);
                return;
            }
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }
            for (String key : props.stringPropertyNames()) {
                if (key.endsWith(".instanceId")) {
                    String serverName = key.substring(0, key.length() - ".instanceId".length());
                    String instanceId = props.getProperty(key);
                    String region = props.getProperty(serverName + ".region", "ca-central-1");
                    serverConfig.put(serverName, new String[]{instanceId, region});
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load ServerNudge config", e);
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getOriginalServer();
        String serverName = target.getServerInfo().getName();

        if (!serverConfig.containsKey(serverName)) {
            return;
        }

	CompletableFuture<ServerPing> pingFuture = target.ping();

        try {
            pingFuture.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            maybeTriggerStart(serverName);
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
	    event.getPlayer()
		.disconnect(Component.text("⏳ Server is starting...", NamedTextColor.GOLD)
        	.appendNewline()
        	.append(Component.text("Try reconnecting in 60s", NamedTextColor.GRAY)));
        }
    }

    private void maybeTriggerStart(String serverName) {
        long now = System.currentTimeMillis();
        Long last = lastTriggered.get(serverName);
        if (last != null && (now - last) < COOLDOWN_MS) {
            return;
        }
        lastTriggered.put(serverName, now);

        String[] cfg = serverConfig.get(serverName);
        String instanceId = cfg[0];
        String region = cfg[1];

        logger.info("Triggering EC2 start for server '{}' (instance {})", serverName, instanceId);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "aws", "ec2", "start-instances",
                    "--instance-ids", instanceId,
                    "--region", region
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.onExit().thenAccept(p ->
                    logger.info("start-instances for {} exited with code {}", serverName, p.exitValue()));
        } catch (IOException e) {
            logger.error("Failed to run aws ec2 start-instances for {}", serverName, e);
        }
    }
}
