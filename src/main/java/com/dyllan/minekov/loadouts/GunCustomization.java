package com.dyllan.minekov.loadouts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public class GunCustomization {

    public static ItemStack getM4() {
        // Retrieve the base gun item from the registry
        Item gun = ForgeRegistries.ITEMS.getValue(new ResourceLocation("tacz", "modern_kinetic_gun"));
        if (gun == null) {
            System.err.println("Failed to find item tacz:modern_kinetic_gun for M4");
            return ItemStack.EMPTY;
        }

        ItemStack stack = new ItemStack(gun);
        CompoundTag tag = new CompoundTag();

        // Apply gun metadata
        tag.putString("GunId", "tacz:m4a1");
        tag.putInt("GunCurrentAmmoCount", 30); // Can vary per weapon
        tag.putString("GunFireMode", "AUTO");
        tag.putBoolean("HasBulletInBarrel", true);

        // Future: add attachments here

        stack.setTag(tag);
        return stack;
    }

    // Future:
    // public static ItemStack getAK() { ... }
    // public static ItemStack getVector() { ... }
    // public static ItemStack withAttachments(ItemStack gun, String... attachmentIds) { ... }
}
