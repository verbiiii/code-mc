package com.dyllan.minekov.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class SceneEncoder {
    private static final int SIZE_X = 256;
    private static final int SIZE_Y = 256;
    private static final int SIZE_Z = 10;

    private final byte[] buffer = new byte[SIZE_X * SIZE_Y * SIZE_Z];

    public byte[] encodeScene(Level level, BlockPos center) {
        int idx = 0;
        for (int dx = -SIZE_X / 2; dx < SIZE_X / 2; dx++) {
            for (int dy = -SIZE_Y / 2; dy < SIZE_Y / 2; dy++) {
                for (int dz = -SIZE_Z / 2; dz < SIZE_Z / 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    buffer[idx++] = (byte)(state.isSolidRender(level, pos) ? 1 : 0);
                }
            }
        }
        return buffer;
    }
}
