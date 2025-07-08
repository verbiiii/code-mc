package com.dyllan.minekov.training;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class TrainingTickHandler {
    private final TrainingState trainingState;

    public TrainingTickHandler(TrainingState state) {
        this.trainingState = state;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            trainingState.tick();
        }
    }
}
