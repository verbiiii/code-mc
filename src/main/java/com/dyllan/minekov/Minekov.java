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

import com.dyllan.minekov.LootLoader;
import com.dyllan.minekov.ModEntities;
import com.dyllan.minekov.entities.AIOperator;

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
        );
    }
}
