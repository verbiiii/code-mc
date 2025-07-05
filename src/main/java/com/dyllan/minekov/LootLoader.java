package com.dyllan.minekov;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.*;

public class LootLoader {
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    public static void openLootChest(ServerPlayer player, String tableId, MinecraftServer server) {
        try {
            ResourceLocation fileLoc = new ResourceLocation("minekov", "loot/" + tableId);
            InputStreamReader reader = new InputStreamReader(
                Objects.requireNonNull(server.getResourceManager()
                        .getResource(fileLoc.withPath("loot/" + tableId + ".json")).get().open())
            );

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> raw = GSON.fromJson(reader, type);
            int maxItems = ((Double) raw.getOrDefault("max_items", 5)).intValue();

            Map<String, Double> weights = (Map<String, Double>) raw.get("items");
            List<ItemStack> chosen = chooseWeightedItems(weights, RANDOM.nextInt(maxItems) + 1);

            // if (!chosen.isEmpty()) {
                // player.sendSystemMessage(Component.literal("You received:"));
                // for (ItemStack stack : chosen) {
                    // player.sendSystemMessage(Component.literal("- " + stack.getHoverName().getString()));
                // }
            // } else {
                // player.sendSystemMessage(Component.literal("No loot selected."));
            // }

            // Now actually show the chest GUI with loot
            player.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.literal("Weaponry Tier 1");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inv, net.minecraft.world.entity.player.Player playerEntity) {
                    SimpleContainer container = new SimpleContainer(27);
                    for (int i = 0; i < chosen.size(); i++) {
                        container.setItem(i, chosen.get(i));
                    }
                    return ChestMenu.threeRows(id, inv, container);
                }
            });

        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Failed to load loot table: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    private static List<ItemStack> chooseWeightedItems(Map<String, Double> weights, int count) {
        List<ItemStack> result = new ArrayList<>();

        List<Map.Entry<ResourceLocation, Double>> entries = new ArrayList<>();
        double totalWeight = 0.0;

        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            try {
                ResourceLocation id = new ResourceLocation(entry.getKey());
                Double weight = entry.getValue();
                if (ForgeRegistries.ITEMS.containsKey(id) && weight > 0) {
                    entries.add(Map.entry(id, weight));
                    totalWeight += weight;
                }
            } catch (Exception ignored) {
                // Skip invalid keys
            }
        }

        if (entries.isEmpty() || totalWeight <= 0) return result;

        for (int i = 0; i < count; i++) {
            double r = RANDOM.nextDouble() * totalWeight;
            double cumulative = 0.0;
            for (Map.Entry<ResourceLocation, Double> entry : entries) {
                cumulative += entry.getValue();
                if (r <= cumulative) {
                    ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(entry.getKey()));
                    result.add(stack);
                    break;
                }
            }
        }

        return result;
    }
}
