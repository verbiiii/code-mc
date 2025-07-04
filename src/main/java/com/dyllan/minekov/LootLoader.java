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
import net.minecraft.world.item.Items;
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
                Objects.requireNonNull(server.getResourceManager().getResource(fileLoc.withPath("loot/" + tableId + ".json")).get().open())
            );

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> raw = GSON.fromJson(reader, type);
            int maxItems = ((Double) raw.getOrDefault("max_items", 5)).intValue();

            Map<String, Double> weights = (Map<String, Double>) raw.get("items");
            List<ItemStack> chosen = chooseWeightedItems(weights, RANDOM.nextInt(maxItems) + 1);

            SimpleContainer container = new SimpleContainer(54);
            for (int i = 0; i < chosen.size(); i++) {
                container.setItem(i, chosen.get(i));
            }

            AbstractContainerMenu menu = ChestMenu.sixRows(0, player.getInventory(), container);
            player.openMenu(new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.literal("Weaponry Tier 1");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inv, net.minecraft.world.entity.player.Player player) {
                    return menu;
                }
            });
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Failed to load loot table: " + e.getMessage()));
            e.printStackTrace();
        }
    }

    private static List<ItemStack> chooseWeightedItems(Map<String, Double> weights, int count) {
        List<ItemStack> result = new ArrayList<>();
        List<Map.Entry<String, Double>> entries = new ArrayList<>(weights.entrySet());

        double totalWeight = entries.stream().mapToDouble(Map.Entry::getValue).sum();

        for (int i = 0; i < count; i++) {
            double r = RANDOM.nextDouble() * totalWeight;
            double running = 0;
            for (Map.Entry<String, Double> entry : entries) {
                running += entry.getValue();
                if (r <= running) {
                    ResourceLocation itemId = new ResourceLocation(entry.getKey());
                    ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(itemId));
                    result.add(stack);
                    break;
                }
            }
        }

        return result;
    }
}
