package com.dyllan.minekov.training;

public enum TrainingGameMode {
    ONE_VS_ONE,
    FREE_FOR_ALL;

    public static TrainingGameMode fromString(String s) {
        return switch (s.toLowerCase()) {
            case "1v1", "one_vs_one" -> ONE_VS_ONE;
            case "ffa", "free_for_all" -> FREE_FOR_ALL;
            default -> throw new IllegalArgumentException("Unknown game mode: " + s);
        };
    }
}
