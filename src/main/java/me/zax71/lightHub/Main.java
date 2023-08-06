package me.zax71.lightHub;

import dev.rollczi.litecommands.minestom.LiteMinestomFactory;
import me.zax71.lightHub.blocks.Sign;
import me.zax71.lightHub.blocks.Skull;
import me.zax71.lightHub.commands.arguments.PlayerArgument;
import me.zax71.lightHub.listeners.PlayerBlockBreak;
import me.zax71.lightHub.listeners.PlayerLogin;
import me.zax71.lightHub.listeners.PlayerMove;
import me.zax71.lightHub.utils.FullbrightDimension;
import me.zax71.lightHub.utils.NPC;
import net.hollowcube.polar.PolarLoader;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.extras.MojangAuth;
import net.minestom.server.extras.velocity.VelocityProxy;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.server.utils.NamespaceID;
import net.minestom.server.world.biomes.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;
import redis.clients.jedis.Jedis;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static me.zax71.lightHub.utils.ConfigUtils.getOrSetDefault;
import static me.zax71.lightHub.utils.ConfigUtils.initConfig;

public class Main {
    public static InstanceContainer HUB;
    public static CommentedConfigurationNode CONFIG;
    public static HoconConfigurationLoader LOADER;
    public static final Logger logger = LoggerFactory.getLogger(Main.class);
    public static Jedis REDIS;

    public static void main(String[] args) {
        initConfig();
        // Server Initialization
        MinecraftServer minecraftServer = MinecraftServer.init();

        // Register events
        GlobalEventHandler globalEventHandler = MinecraftServer.getGlobalEventHandler();
        EventNode<Event> entityNode = EventNode.type("listeners", EventFilter.ALL);
        entityNode
                .addListener(new PlayerLogin())
                .addListener(new PlayerMove())
                .addListener(new PlayerBlockBreak());
        globalEventHandler.addChild(entityNode);

        // Register block handlers
        MinecraftServer.getBlockManager().registerHandler(NamespaceID.from("minecraft:sign"), Sign::new);
        MinecraftServer.getBlockManager().registerHandler(NamespaceID.from("minecraft:skull"), Skull::new);


        switch (getOrSetDefault(CONFIG.node("connection", "mode"), "online")) {
            case "online" -> MojangAuth.init();
            case "velocity" -> {
                String velocitySecret = getOrSetDefault(CONFIG.node("connection", "velocitySecret"), "");
                if (!Objects.equals(velocitySecret, "")) {
                    VelocityProxy.enable(velocitySecret);
                }
            }
        }


        // Start the server on port 25565
        minecraftServer.start("0.0.0.0", Integer.parseInt(getOrSetDefault(CONFIG.node("connection", "port"), "25565")));
        initCommands();
        initWorlds();

        // Create the team to turn off collisions and make players partially visible
        MinecraftServer.getTeamManager().createBuilder("noCollision")
                .collisionRule(TeamsPacket.CollisionRule.NEVER)
                .updateTeamPacket()
                .build();


        // Init Redis
        REDIS = new Jedis(
                getOrSetDefault(CONFIG.node("database", "redis", "hostname"), "localhost"),
                Integer.parseInt(getOrSetDefault(CONFIG.node("database", "redis", "port"), "6379"))
        );

        // Add event listener for click event on NPCs
        for (NPC npc : NPC.spawnNPCs(HUB)) {
            HUB.eventNode().addListener(EntityAttackEvent.class, npc::handle)
                    .addListener(PlayerEntityInteractEvent.class, npc::handle);
        }

    }

    public static Path getPath(String path) {
        try {
            return Path.of(
                            new File(
                                    Main.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI()
                            ).getPath()
                    ).getParent()
                    .resolve(path);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private static void initCommands() {
        LiteMinestomFactory.builder(MinecraftServer.getServer(), MinecraftServer.getCommandManager())
                // .commandInstance(new Command())
                .argument(Player.class, new PlayerArgument(MinecraftServer.getServer()))
                .register();
    }

    private static void initWorlds() {

        // Register minecraft:the_void
        MinecraftServer.getBiomeManager().addBiome(Biome
                .builder()
                .name(NamespaceID.from("minecraft:the_void"))
                .build()
        );

        // Fail and stop server if hub doesn't exist
        if (!getPath("config/worlds/hub.polar").toFile().exists()) {
            logger.error("Missing hub world, please place a Polar world at ./config/worlds/hub.polar and restart the server");
            MinecraftServer.stopCleanly();
            return;
        }

        // Load hub now we know it exists
        try {
            HUB = MinecraftServer.getInstanceManager().createInstanceContainer(
                    FullbrightDimension.INSTANCE,
                    new PolarLoader(getPath("config/worlds/hub.polar"))
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logger.info("Loaded Hub world");
        HUB.setTimeRate(0);
    }

}