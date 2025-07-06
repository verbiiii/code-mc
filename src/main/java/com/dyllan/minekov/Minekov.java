package com.dyllan.minekov;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerPlayer;

import com.dyllan.minekov.scene.SceneEncoder;

@Mod(Minekov.MODID)
public class Minekov {
    public static final String MODID = "minekov";

    public Minekov() {
        MinecraftForge.EVENT_BUS.register(this);
        ModEntities.register();
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
                    SceneEncoder encoder = new SceneEncoder();
                    byte[] volume = encoder.encodeScene(player.level(), player.blockPosition());

                    int solidCount = 0;
                    for (byte b : volume) {
                        if (b == 1) solidCount++;
                    }

                    // ✅ Send to Python Dash server
                    PythonBridge.sendSceneVolume(volume);

                    player.sendSystemMessage(Component.literal("Scene scan: " + solidCount + " solid blocks (sent to dashboard)"));
                    return 1;
                })
            )
        );
    }
}
