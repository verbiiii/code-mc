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

        ResourceLocation itemId = new ResourceLocation("tacz", "modern_kinetic_gun");
        Item gun = ForgeRegistries.ITEMS.getValue(itemId);

        if (gun != null) {
            ItemStack stack = new ItemStack(gun);

            // Add NBT: GunId = "tacz:m4a1"
            CompoundTag nbt = new CompoundTag();
            nbt.putString("GunId", "tacz:m4a1");
            stack.setTag(nbt);

            this.setItemSlot(EquipmentSlot.MAINHAND, stack);
        } else {
            System.err.println("Could not find item tacz:modern_kinetic_gun — is the mod loaded?");
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
