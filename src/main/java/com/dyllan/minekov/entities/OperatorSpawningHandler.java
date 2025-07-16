package com.dyllan.minekov.entities;

import com.dyllan.minekov.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

public class OperatorSpawningHandler {

    private static final int MAX_ATTEMPTS = 50;
    private static final int SPAWN_RADIUS = 25;

    public static RLOperator spawnRLOperator(ServerLevel world, Player referencePlayer) {
        System.out.println("Spawning RLOperator");
        BlockPos spawnPos = findValidSpawn(world, referencePlayer);
        if (spawnPos == null) throw new IllegalStateException("Failed to find valid spawn location for RLOperator");

        RLOperator rlOp = ModEntities.RL_OPERATOR.get().create(world);
        rlOp.moveTo(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
        world.addFreshEntity(rlOp);
        return rlOp;
    }

    public static DumbOperator spawnDumbOperator(ServerLevel world, Player referencePlayer) {
        BlockPos spawnPos = findValidSpawn(world, referencePlayer);
        if (spawnPos == null) throw new IllegalStateException("Failed to find valid spawn location for DumbOperator");

        DumbOperator dumb = ModEntities.DUMB_OPERATOR.get().create(world);
        dumb.moveTo(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5, 0, 0);
        world.addFreshEntity(dumb);
        return dumb;
    }

    private static BlockPos findValidSpawn(ServerLevel world, Player player) {
        Random rand = new Random();
        BlockPos basePos = player.blockPosition();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int dx = rand.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;
            int dz = rand.nextInt(SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;
            BlockPos offsetPos = basePos.offset(dx, 0, dz);

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

    // private static boolean isValidSpawnSurface(ServerLevel world, BlockPos pos) {
    //     BlockState state = world.getBlockState(pos);
    //     return state.isCollisionShapeFullBlock(world, pos);
    // }
}
