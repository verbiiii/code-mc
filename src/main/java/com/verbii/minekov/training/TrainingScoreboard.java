package com.verbii.minekov.training;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.Objective;

import com.verbii.minekov.entities.AIOperator;

import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class TrainingScoreboard {

    private static MinecraftServer server;

    public static void setServer(MinecraftServer srv) {
        server = srv;
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (server == null) return;

        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof AIOperator ai)) return;

        String subclassName = ai.getClass().getSimpleName();
        Scoreboard scoreboard = server.getScoreboard();

        // Ensure the objective exists for this subclass
        Objective obj = scoreboard.getObjective("ai_kills");
        if (obj == null) {
            obj = scoreboard.addObjective(
                "ai_kills",
                ObjectiveCriteria.DUMMY,
                Component.literal("AI Kills"),
                RenderType.INTEGER
            );
        }

        // Increment the score for this specific subclass name
        scoreboard.getOrCreatePlayerScore(subclassName, obj).add(1);
    }
}
