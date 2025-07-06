package com.dyllan.minekov.entities;

import com.dyllan.minekov.entities.ai.goals.GunAttackGoal;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.entity.shooter.*;
import com.tacz.guns.entity.sync.ModSyncedEntityData;
import com.tacz.guns.item.ModernKineticGunItem;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.GunData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import com.dyllan.minekov.loadouts.GunCustomization;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Supplier;

public class AIOperator extends PathfinderMob implements IGunOperator {
    private final LivingEntity shooter = this;
    private final ShooterDataHolder data = new ShooterDataHolder();
    private final LivingEntityDrawGun draw = new LivingEntityDrawGun(shooter, data);
    private final LivingEntityAim aim = new LivingEntityAim(shooter, data);
    private final LivingEntityCrawl crawl = new LivingEntityCrawl(shooter, data);
    private final LivingEntityAmmoCheck ammoCheck = new LivingEntityAmmoCheck(shooter);
    private final LivingEntityFireSelect fireSelect = new LivingEntityFireSelect(shooter, data);
    private final LivingEntityMelee melee = new LivingEntityMelee(shooter, data, draw);
    private final LivingEntityShoot shoot = new LivingEntityShoot(shooter, data, draw);
    private final LivingEntityBolt bolt = new LivingEntityBolt(data, shooter, draw, shoot);
    private final LivingEntityReload reload = new LivingEntityReload(shooter, data, draw, shoot);
    private final LivingEntitySpeedModifier speed = new LivingEntitySpeedModifier(shooter, data);
    private final LivingEntitySprint sprint = new LivingEntitySprint(shooter, data);

    private boolean drawn = false;

    public AIOperator(EntityType<AIOperator> type, Level level) {
        super(type, level);
        initialData();
    
        // give them an M4
        ItemStack gun = GunCustomization.getM4();
        this.setItemSlot(EquipmentSlot.MAINHAND, gun);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // this.goalSelector.addGoal(1, new WatchClosestVisiblePlayerGoal(this, 64.0D));
        this.goalSelector.addGoal(1, new GunAttackGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            if (getMainHandItem().getItem() instanceof ModernKineticGunItem) {
                if (!drawn) {
                    draw(() -> getMainHandItem());
                }
                bolt();
                reload.tickReloadState();
                aim.tickAimingProgress();
                aim.tickSprint();
                crawl.tickCrawling();
                bolt.tickBolt();
                melee.scheduleTickMelee();
                speed.updateSpeedModifier();
                shooter.setSprinting(sprint.getProcessedSprintStatus(shooter.isSprinting()));
                syncGunData();
            }
        }
    }

    private void syncGunData() {
        ModSyncedEntityData.SHOOT_COOL_DOWN_KEY.setValue(shooter, shoot.getShootCoolDown());
        ModSyncedEntityData.MELEE_COOL_DOWN_KEY.setValue(shooter, melee.getMeleeCoolDown());
        ModSyncedEntityData.DRAW_COOL_DOWN_KEY.setValue(shooter, draw.getDrawCoolDown());
        ModSyncedEntityData.IS_BOLTING_KEY.setValue(shooter, data.isBolting);
        ModSyncedEntityData.RELOAD_STATE_KEY.setValue(shooter, reload.tickReloadState());
        ModSyncedEntityData.AIMING_PROGRESS_KEY.setValue(shooter, data.aimingProgress);
        ModSyncedEntityData.IS_AIMING_KEY.setValue(shooter, data.isAiming);
        ModSyncedEntityData.SPRINT_TIME_KEY.setValue(shooter, data.sprintTimeS);
    }

    public void initialData() {
        data.initialData();
        AttachmentPropertyManager.postChangeEvent(shooter, shooter.getMainHandItem());
    }

    public void draw(Supplier<ItemStack> gunItemSupplier) {
        draw.draw(gunItemSupplier);
        drawn = true;
    }

    public void aim(boolean isAim) {
        aim.aim(isAim);
    }

    public void crawl(boolean isCrawl) {
        crawl.crawl(isCrawl);
    }

    public void fireSelect() {
        fireSelect.fireSelect();
    }

    public void bolt() {
        bolt.bolt();
    }

    public void reload() {
        reload.reload();
    }

    public void cancelReload() {
        reload.cancelReload();
    }

    public void melee() {
        melee.melee();
    }

    public ShootResult shoot(Supplier<Float> pitch, Supplier<Float> yaw) {
        return shoot(pitch, yaw, System.currentTimeMillis() - data.baseTimestamp);
    }

    public ShootResult shoot(Supplier<Float> pitch, Supplier<Float> yaw, long timestamp) {
        return shoot.shoot(pitch, yaw, timestamp);
    }

    public boolean consumesAmmoOrNot() {
        return ammoCheck.consumesAmmoOrNot();
    }

    public boolean nextBulletIsTracer(int tracerCountInterval) {
        ++data.shootCount;
        return tracerCountInterval != -1 && data.shootCount % (tracerCountInterval + 1) == 0;
    }

    public void updateCacheProperty(@Nullable AttachmentCacheProperty property) {
        data.cacheProperty = property;
    }

    @Nullable
    public AttachmentCacheProperty getCacheProperty() {
        return data.cacheProperty;
    }

    public ShooterDataHolder getDataHolder() {
        return data;
    }

    @Override
    public long getSynShootCoolDown() {
        return shoot.getShootCoolDown();
    }

    @Override
    public long getSynMeleeCoolDown() {
        return melee.getMeleeCoolDown();
    }

    @Override
    public long getSynDrawCoolDown() {
        return draw.getDrawCoolDown();
    }

    @Override
    public boolean getSynIsBolting() {
        return data.isBolting;
    }

    @Override
    public ReloadState getSynReloadState() {
        return (ReloadState)ModSyncedEntityData.RELOAD_STATE_KEY.getValue(shooter);
    }

    @Override
    public float getSynAimingProgress() {
        return data.aimingProgress;
    }

    @Override
    public boolean getSynIsAiming() {
        return data.isAiming;
    }

    @Override
    public float getSynSprintTime() {
        return data.sprintTimeS;
    }

    @Override
    public boolean getProcessedSprintStatus(boolean sprinting) {
        return sprint.getProcessedSprintStatus(sprinting);
    }

    @Override
    public boolean needCheckAmmo() {
        return ammoCheck.needCheckAmmo();
    }

    @Override
    public void zoom() {
        // Optional: implement if your AI needs scope zoom control
        // For now we can just leave this empty
    }

}
