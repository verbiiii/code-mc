package com.dyllan.minekov;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.dyllan.minekov.entities.OperatorSpawningHandler;
import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.scene.SceneEncoder;
import com.dyllan.minekov.training.TrainingGameMode;
import com.dyllan.minekov.training.TrainingIsolationHandler;
import com.dyllan.minekov.training.TrainingScoreboard;
import com.dyllan.minekov.training.TrainingState;
import com.eliotlash.mclib.math.Operator;

@Mod(Minekov.MODID)
@EventBusSubscriber(modid = Minekov.MODID, bus = Bus.FORGE)
public class Minekov {
    public static final String MODID = "minekov";

    public static TrainingState trainingState = null;

    private static PythonRLController pythonController;
    private static int tickCounter = 0;
    private static final int RECONNECT_INTERVAL = 20; // try every second

    public Minekov() {
        MinecraftForge.EVENT_BUS.register(this);
        ModEntities.register();
        initPythonConnection();

        MinecraftForge.EVENT_BUS.register(TrainingIsolationHandler.class);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("minekov")
                .then(Commands.literal("loot")
                    .then(Commands.argument("table", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            builder.suggest("weaponry_tier1");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String table = StringArgumentType.getString(context, "table");
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            LootLoader.openLootChest(player, table, context.getSource().getServer());
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("scene")
                    .executes(context -> runSceneCommand(context.getSource().getPlayerOrException(), 32, 8, 32))
                    .then(Commands.argument("x_length", IntegerArgumentType.integer(1))
                        .executes(context -> {
                            int x = IntegerArgumentType.getInteger(context, "x_length");
                            return runSceneCommand(context.getSource().getPlayerOrException(), x, 8, 32);
                        })
                        .then(Commands.argument("y_length", IntegerArgumentType.integer(1))
                            .executes(context -> {
                                int x = IntegerArgumentType.getInteger(context, "x_length");
                                int y = IntegerArgumentType.getInteger(context, "y_length");
                                return runSceneCommand(context.getSource().getPlayerOrException(), x, y, 32);
                            })
                            .then(Commands.argument("z_length", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    int x = IntegerArgumentType.getInteger(context, "x_length");
                                    int y = IntegerArgumentType.getInteger(context, "y_length");
                                    int z = IntegerArgumentType.getInteger(context, "z_length");
                                    return runSceneCommand(context.getSource().getPlayerOrException(), x, y, z);
                                })
                            )
                        )
                    )
                )
                .then(Commands.literal("start")
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                            .executes(ctx -> {
                                TrainingGameMode mode = TrainingGameMode.fromString(StringArgumentType.getString(ctx, "mode"));
                                ServerPlayer player = ctx.getSource().getPlayerOrException();
                                BlockPos center = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                return runTrainCommand(player, player.serverLevel(), mode, 2048, center, 16);
                            })
                            .then(Commands.argument("rounds", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    TrainingGameMode mode = TrainingGameMode.fromString(StringArgumentType.getString(ctx, "mode"));
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    BlockPos center = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                    int rounds = IntegerArgumentType.getInteger(ctx, "rounds");
                                    return runTrainCommand(player, player.serverLevel(), mode, rounds, center, 16);
                                })
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        TrainingGameMode mode = TrainingGameMode.fromString(StringArgumentType.getString(ctx, "mode"));
                                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                                        BlockPos center = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                                        int rounds = IntegerArgumentType.getInteger(ctx, "rounds");
                                        int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                        return runTrainCommand(player, player.serverLevel(), mode, rounds, center, radius);
                                    })
                                )
                            )
                        )
                    )
                )
                    .then(Commands.literal("stop")
                        .executes(ctx -> {
                            if (trainingState != null) {
                                trainingState.stop();
                                trainingState = null;
                                ctx.getSource().getServer().getPlayerList().broadcastSystemMessage(
                                    Component.literal("Training session forcefully stopped."), false
                                );
                                return 1;
                            } else {
                                ctx.getSource().sendFailure(Component.literal("No active training session."));
                                return 0;
                            }
                        })
                    )
                )
                .then(Commands.literal("play")
                    .executes(ctx -> {
                        return runPlayCommand(ctx.getSource().getPlayerOrException(), ctx.getSource().getLevel());
                    })
                )
        );
    }


    private static int runTrainCommand(ServerPlayer player, ServerLevel world, TrainingGameMode mode, int rounds, BlockPos centerPosition, int spawnRadius) {
        OperatorSpawningHandler operatorSpawningHandler = new OperatorSpawningHandler(world, centerPosition, spawnRadius);
        trainingState = new TrainingState(player, world.getServer(), rounds, operatorSpawningHandler, mode);

        TrainingScoreboard.setServer(world.getServer());
        Scoreboard scoreboard = world.getServer().getScoreboard();
        Objective obj = scoreboard.getObjective("ai_kills");
        scoreboard.setDisplayObjective(1, obj);

        world.getServer().getPlayerList().broadcastSystemMessage(
            Component.literal("Training initialized (" + mode + "). Rounds: " + rounds), false
        );

        return 1;
    }


    private static int runSceneCommand(ServerPlayer player, int xLength, int yLength, int zLength) {
        SceneEncoder encoder = new SceneEncoder(xLength, yLength, zLength);
        byte[] volume = encoder.encodeScene(player.level(), player.blockPosition());

        int solidCount = 0;
        for (byte b : volume) {
            if (b == 1) solidCount++;
        }

        PythonBridge.sendSceneVolume(encoder);

        player.sendSystemMessage(Component.literal(
            "Scene scan: " + solidCount + " solid blocks (sent to dashboard @ "
            + xLength + "×" + yLength + "×" + zLength + ")"
        ));

        return 1;
    }



    private void initPythonConnection() {
        try {
            URI uri = new URI("ws://127.0.0.1:8050/socket");
            pythonController = new PythonRLController(uri);
            pythonController.connect();
            PythonBridge.rlController = pythonController;
        } catch (Exception e) {
            // Silent initial connection failure - no console spam
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter >= RECONNECT_INTERVAL) {
            tickCounter = 0;

            if (pythonController == null || !pythonController.isConnected()) {
                // Silent reconnection attempt - no console spam
                try {
                    URI uri = new URI("ws://127.0.0.1:8050/socket");
                    pythonController = new PythonRLController(uri);
                    pythonController.connect();
                    PythonBridge.rlController = pythonController; // TODO: move this into the python socket connection automatically somehow
                } catch (Exception e) {
                    // Silent reconnection failure - no console spam
                }
            }
        }

        // ✅ TICK THE TRAINING STATE IF ACTIVE
        if (trainingState != null) {
            trainingState.tick();

            // clear instance if we're done
            if (trainingState.isComplete()) {
                trainingState = null;
            }
        }
    }

    public static void sendToPython(String message) {
        if (pythonController != null && pythonController.isConnected()) {
            pythonController.sendToPython(message);
        } else {
            System.err.println("[Minekov] Python WebSocket is not open.");
        }
    }

    @SubscribeEvent
    public static void onRLCombatEvent(LivingHurtEvent event) {
        // If victim is an RL operator, track damage taken
        if (event.getEntity() instanceof RLOperator victim) {
            victim.addDamageTaken(event.getAmount());
        }

        // If attacker is an RL operator, track damage dealt
        if (event.getSource().getEntity() instanceof RLOperator attacker) {
            attacker.addDamageDealt(event.getAmount());
        }
    }

    @SubscribeEvent
    public static void onOperatorDeath(LivingDeathEvent event) {
        LivingEntity victimEntity = event.getEntity();
        Entity attackerEntity = event.getSource().getEntity();

        // Check for 1v1 combat outcome (player vs AI)
        if (victimEntity instanceof ServerPlayer player && attackerEntity instanceof RLOperator) {
            // Player lost to AI - round ends
            sendRoundEnd(false); // Send round end without model updates
            player.sendSystemMessage(Component.literal("§c💀 You have been defeated by the AI! Better luck next time."));
            player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§c🤖 The AI has defeated " + player.getName().getString() + " in 1v1 combat!"), false
            );
            player.sendSystemMessage(Component.literal("§7🔄 Use /minekov play to start a new round."));
            return;
        } else if (victimEntity instanceof RLOperator && attackerEntity instanceof ServerPlayer player) {
            // Player won against AI - round ends
            sendRoundEnd(false); // Send round end without model updates
            player.sendSystemMessage(Component.literal("§a🏆 Victory! You have defeated the AI agent!"));
            player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§a👑 " + player.getName().getString() + " has defeated the AI agent!"), false
            );
            player.sendSystemMessage(Component.literal("§7🔄 Use /minekov play to start a new round."));
            return;
        }

        // Regular training logic (both must be AI operators)
        if (!(victimEntity instanceof RLOperator) && !(attackerEntity instanceof RLOperator)) {
            return; // nothing to track
        }

        // Only RL operators track deaths
        if (victimEntity instanceof RLOperator victim) {
            victim.addDeath();
            // Silent death tracking - no console spam
        }

        // Only RL operators track kills
        if (attackerEntity instanceof RLOperator attacker) {
            attacker.addKill();
            // Silent kill tracking - no console spam
        }
    }

    private static int runPlayCommand(ServerPlayer player, ServerLevel world) {
        // Check if there's already a training session running
        if (trainingState != null) {
            player.sendSystemMessage(Component.literal("§c⚠️ Cannot start play mode while training is active. Stop training first."));
            return 0;
        }

        // Use existing Python controller - it already works
        if (pythonController == null || !pythonController.isConnected()) {
            player.sendSystemMessage(Component.literal("§c⚠️ Python controller not connected. Start it first."));
            return 0;
        }
        
        // Use the same spawn positions as training
        double playerX = 19.5, playerZ = 17.5; // Player spawn (team1 position)
        double aiX = 19.5, aiZ = 9.5; // AI spawn (team2 position)
        double y = 2.0;

        // Teleport player to spawn position
        player.teleportTo(playerX, y + 1, playerZ); // +1 to spawn above ground
        player.setYRot(180.0f); // Face towards AI spawn
        player.sendSystemMessage(Component.literal("§e⚔️ Teleported to combat arena!"));

        // Spawn ONE RLOperator (for 1v1)
        RLOperator topAgent = ModEntities.RL_OPERATOR.get().create(world);
        if (topAgent != null) {
            topAgent.moveTo(aiX, y, aiZ, 0.0f, 0.0f); // Face towards player
            topAgent.setPlayerAttackMode(true); // Enable player targeting
            world.addFreshEntity(topAgent);
            
            player.sendSystemMessage(Component.literal("§a🤖 AI agent spawned! It will use the existing training AI. Prepare for battle!"));
            player.sendSystemMessage(Component.literal("§7💡 The AI will target you specifically in this mode."));
            player.sendSystemMessage(Component.literal("§7🏆 Fight until one of you dies - winner takes all!"));
            
            // Broadcast to server
            world.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("§6🥊 " + player.getName().getString() + " is fighting the top AI agent!"), false
            );
        } else {
            player.sendSystemMessage(Component.literal("§c⚠️ Failed to spawn AI agent."));
            return 0;
        }

        return 1;
    }
    
    /**
     * Send round end signal with configurable model update flag
     */
    public static void sendRoundEnd(boolean updateModelParameters) {
        Map<String, Object> roundEndData = new HashMap<>();
        roundEndData.put("type", "round_end");
        roundEndData.put("update_model_parameters", updateModelParameters);
        PythonBridge.tickPython(roundEndData);
    }
}
