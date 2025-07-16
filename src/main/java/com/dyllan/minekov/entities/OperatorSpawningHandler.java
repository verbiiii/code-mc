package com.dyllan.minekov.entities;

import com.dyllan.minekov.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

public class OperatorSpawningHandler {
    private static final int MAX_ATTEMPTS = 50;

    private final ServerLevel world;
    private final BlockPos centerPos;
    private final int spawnRadius;
    private final Random rand = new Random();

    public OperatorSpawningHandler(ServerLevel world, BlockPos centerPos, int spawnRadius) {
        this.world = world;
        this.centerPos = centerPos;
        this.spawnRadius = spawnRadius;
    }

    public RLOperator spawnRLOperator() {
        System.out.println("Spawning RLOperator");
        BlockPos spawnPos = findValidSpawn();
        if (spawnPos == null) throw new IllegalStateException("Failed to find valid spawn location for RLOperator");

        RLOperator rlOp = ModEntities.RL_OPERATOR.get().create(world);
        rlOp.moveTo(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
        world.addFreshEntity(rlOp);
        return rlOp;
    }

    public DumbOperator spawnDumbOperator() {
        BlockPos spawnPos = findValidSpawn();
        if (spawnPos == null) throw new IllegalStateException("Failed to find valid spawn location for DumbOperator");

        DumbOperator dumb = ModEntities.DUMB_OPERATOR.get().create(world);
        dumb.moveTo(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
        world.addFreshEntity(dumb);
        return dumb;
    }

    private BlockPos findValidSpawn() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int dx = rand.nextInt(spawnRadius * 2 + 1) - spawnRadius;
            int dz = rand.nextInt(spawnRadius * 2 + 1) - spawnRadius;
            BlockPos offsetPos = centerPos.offset(dx, 0, dz);

            BlockPos surfacePos = world.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, offsetPos);
            BlockPos below = surfacePos.below();

            BlockState belowState = world.getBlockState(below);
            boolean valid = belowState.isCollisionShapeFullBlock(world, below);

            System.out.println("Attempt " + attempt + ": trying " + surfacePos + " -> valid=" + valid + " (" + belowState.getBlock().getName().getString() + ")");

            if (valid) {
                return surfacePos;
            }
        }

        System.out.println("❌ Failed to find valid spawn after " + MAX_ATTEMPTS + " attempts");
        return null;
    }
}
