package com.dyllan.minekov;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.entities.RLOperatorRegistry;
import com.dyllan.minekov.scene.SceneEncoder;

@Mod(Minekov.MODID)
@EventBusSubscriber(modid = Minekov.MODID, bus = Bus.FORGE)
public class Minekov {
    public static final String MODID = "minekov";

    private static PythonWebSocketClient pythonSocket;
    private static int tickCounter = 0;
    private static final int RECONNECT_INTERVAL = 20; // try every second

    public Minekov() {
        MinecraftForge.EVENT_BUS.register(this);
        ModEntities.register();
        initPythonConnection();
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
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        SceneEncoder encoder = new SceneEncoder(256, 10, 256);
                        byte[] volume = encoder.encodeScene(player.level(), player.blockPosition());

                        int solidCount = 0;
                        for (byte b : volume) {
                            if (b == 1) solidCount++;
                        }

                        // ✅ Send to Python Dash server
                        PythonBridge.sendSceneVolume(encoder);

                        player.sendSystemMessage(Component.literal("Scene scan: " + solidCount + " solid blocks (sent to dashboard)"));
                        return 1;
                    })
                )
        );
    }

    private void initPythonConnection() {
        try {
            URI uri = new URI("ws://127.0.0.1:8050/socket");
            pythonSocket = new PythonWebSocketClient(uri);
            pythonSocket.connect();
        } catch (Exception e) {
            System.err.println("[Minekov] Failed to connect to Python dashboard:");
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter >= RECONNECT_INTERVAL) {
            tickCounter = 0;

            if (pythonSocket == null || !pythonSocket.isConnected()) {
                System.out.println("[Minekov] Attempting to reconnect to Python dashboard...");
                try {
                    URI uri = new URI("ws://127.0.0.1:8050/socket");
                    pythonSocket = new PythonWebSocketClient(uri);
                    pythonSocket.connect();
                } catch (Exception e) {
                    System.err.println("[Minekov] Reconnect failed: " + e.getMessage());
                }
            }
        }

        if (pythonSocket != null && pythonSocket.isConnected()) {
            syncRLOperatorsToPython();
        }
    }

    public static void sendToPython(String message) {
        if (pythonSocket != null && pythonSocket.isConnected()) {
            pythonSocket.send(message);
        } else {
            System.err.println("[Minekov] Python WebSocket is not open.");
        }
    }

    private static void syncRLOperatorsToPython() {
        if (!pythonSocket.isConnected()) return;

        List<Map<String, Object>> operators = new ArrayList<>();

        for (RLOperator op : RLOperatorRegistry.getAll()) {
            if (op.isRemoved() || !op.isAlive()) continue;

            Map<String, Object> info = new HashMap<>();
            info.put("id", op.getUUID().toString());
            info.put("name", op.getName().getString());
            info.put("x", op.getX());
            info.put("y", op.getY());
            info.put("z", op.getZ());
            info.put("health", op.getHealth());

            operators.add(info);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "sync_operators");
        payload.put("agents", operators);

        try {
            String json = new com.google.gson.Gson().toJson(payload);
            pythonSocket.send(json);
        } catch (Exception e) {
            System.err.println("[Minekov] Failed to send operator sync:");
            e.printStackTrace();
        }
    }

}
