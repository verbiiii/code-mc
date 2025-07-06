package com.dyllan.minekov.entities.ai.goals;

import com.dyllan.minekov.entities.AIOperator;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;
import java.util.List;

public class GunAttackGoal extends Goal {
    public static final boolean TARGET_PLAYERS = false; // Set to true to attack players, false to attack other AIOperators

    private final AIOperator mob;
    private LivingEntity target;
    private int cooldown = 0;
    private final double range;

    public GunAttackGoal(AIOperator mob) {
        this.mob = mob;
        this.range = AIOperator.MAX_DISTANCE;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        this.target = TARGET_PLAYERS ? findVisiblePlayer() : findVisibleBot();
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
        System.err.println("[GunAttackGoal] Target acquired: " + target);
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
            cooldown = 3 + mob.getRandom().nextInt(5);
        }
    }

    private LivingEntity findVisiblePlayer() {
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        return players.stream()
            .filter(p -> p.isAlive() && mob.hasLineOfSight(p))
            .min((a, b) -> Double.compare(mob.distanceToSqr(a), mob.distanceToSqr(b)))
            .orElse(null);
    }

    private LivingEntity findVisibleBot() {
        List<AIOperator> bots = mob.level().getEntitiesOfClass(AIOperator.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        return bots.stream()
            .filter(bot -> bot != mob && bot.isAlive() && mob.hasLineOfSight(bot))
            .min((a, b) -> Double.compare(mob.distanceToSqr(a), mob.distanceToSqr(b)))
            .orElse(null);
    }

    private void tryFireGun() {
        if (target == null) return;

        System.err.println("[GunAttackGoal] Attempting to fire");

        double dx = target.getX() - mob.getX();
        double dy = target.getEyeY() - mob.getEyeY();
        double dz = target.getZ() - mob.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

        try {
            mob.shoot(() -> pitch, () -> yaw);
        } catch (Exception e) {
            System.err.println("[GunAttackGoal] shoot() failed: " + e.getMessage());
        }
    }
}
