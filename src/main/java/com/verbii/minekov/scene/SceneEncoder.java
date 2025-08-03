package com.verbii.minekov.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class SceneEncoder {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private byte[] buffer;

    public SceneEncoder(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.buffer = new byte[sizeX * sizeY * sizeZ];
    }

    public byte[] encodeScene(Level level, BlockPos center) {
        int idx = 0;
        for (int dx = -sizeX / 2; dx < sizeX / 2; dx++) {
            for (int dy = -sizeY / 2; dy < sizeY / 2; dy++) {
                for (int dz = -sizeZ / 2; dz < sizeZ / 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    buffer[idx++] = (byte)(state.isSolidRender(level, pos) ? 1 : 0);
                }
            }
        }
        return buffer;
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }
}
