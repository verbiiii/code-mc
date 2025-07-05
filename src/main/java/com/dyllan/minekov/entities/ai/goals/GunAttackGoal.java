package com.dyllan.minekov.entities.ai.goals;

import com.dyllan.minekov.entities.AIOperator;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.List;

public class GunAttackGoal extends Goal {
    private final AIOperator mob;
    private LivingEntity target;
    private int cooldown = 0;
    private final double range;

    public GunAttackGoal(AIOperator mob) {
        this.mob = mob;
        this.range = 64.0D;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        this.target = findVisiblePlayer();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null && this.target.isAlive() &&
               this.mob.hasLineOfSight(target) &&
               this.mob.distanceToSqr(target) < range * range;
    }

    @Override
    public void start() {
        System.err.println("[GunAttackGoal] Target acquired");
    }

    @Override
    public void stop() {
        System.err.println("[GunAttackGoal] Lost sight of target");
        this.target = null;
    }

    @Override
    public void tick() {
        if (target == null) return;

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--cooldown <= 0) {
            tryFireGun();
            cooldown = 20 + mob.getRandom().nextInt(10); // tap fire every ~1-1.5s
        }
    }

    private LivingEntity findVisiblePlayer() {
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        double closestDist = Double.MAX_VALUE;
        Player closest = null;

        for (Player p : players) {
            if (!p.isAlive() || !mob.hasLineOfSight(p)) continue;

            double dist = mob.distanceToSqr(p);
            if (dist < closestDist) {
                closest = p;
                closestDist = dist;
            }
        }

        return closest;
    }

    private void tryFireGun() {
        System.err.println("[GunAttackGoal] Attempting to fire");
        ItemStack gun = mob.getMainHandItem();

        if (gun == null) return;

        Item item = gun.getItem();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);

        if (id != null && id.getNamespace().equals("tacz")) {
            try {
                item.getClass().getMethod("fireGun", Level.class, LivingEntity.class, InteractionHand.class)
                    .invoke(item, mob.level(), mob, InteractionHand.MAIN_HAND);
            } catch (Exception e) {
                System.err.println("[GunAttackGoal] Failed to fire gun: " + e.getMessage());
            }
        }
    }
}
