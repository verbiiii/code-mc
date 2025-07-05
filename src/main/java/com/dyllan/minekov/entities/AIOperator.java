package com.dyllan.minekov.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

public class AIOperator extends Monster {
    public AIOperator(EntityType<? extends Monster> type, Level level) {
        super(type, level);

        // Get the item from the registry using a namespaced ID
        ResourceLocation itemId = new ResourceLocation("superbwarfare", "m_4");
        Item m4 = ForgeRegistries.ITEMS.getValue(itemId);

        if (m4 != null) {
            this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(m4));
        } else {
            System.err.println("Could not find item superbwarfare:m_4 — is the mod loaded?");
        }
    }

    public SpawnGroupData finalizeSpawn(ServerLevel level, DifficultyInstance difficulty, MobSpawnType reason,
                                        @Nullable SpawnGroupData spawnData, @Nullable CompoundTag dataTag) {
        return ForgeEventFactory.onFinalizeSpawn(this, level, difficulty, reason, spawnData, dataTag);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.ATTACK_DAMAGE, 2.0D);
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }
}
