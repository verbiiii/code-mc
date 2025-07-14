package com.dyllan.minekov.entities;

import com.dyllan.minekov.AgentIdManager;
import com.dyllan.minekov.entities.ai.goals.WatchClosestTargetGoal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class RLOperator extends AIOperator {
    private WatchClosestTargetGoal watchGoal;
    private int agentId = 0; // Compact agent ID for binary protocol

    private float damageTakenLastTick = 0f;
    private float damageDealtLastTick = 0f;
    
    private int deaths = 0;
    private int kills = 0;
    
    private int deathsLastTick = 0;
    private int killsLastTick = 0;

    // Player attack mode for 1v1 combat
    private boolean playerAttackMode = false;

    // Aiming control variables
    private boolean useRLAiming = true; // When true, use RL-predicted aiming instead of auto-targeting

    public RLOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        watchGoal = new WatchClosestTargetGoal(this, 64.0D);
        // Only add the watch goal if not using RL aiming
        if (!useRLAiming) {
            this.goalSelector.addGoal(1, watchGoal);
        }
    }

    /**
     * Look in a specific direction using pitch and yaw.
     * This method extracts the core targeting logic from WatchClosestTargetGoal
     * to allow direct RL control over aiming.
     * 
     * @param pitch Vertical rotation in degrees (negative = looking up, positive = looking down)
     * @param yaw Horizontal rotation in degrees (0 = north, 90 = east, 180 = south, 270 = west)
     */
    public void lookInDirection(float pitch, float yaw) {
        // Update mob look with the provided pitch and yaw
        this.setXRot(pitch);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        
        // Update look control to maintain consistency with vanilla AI systems
        // Calculate a target position based on the look direction for the look control system
        double lookDistance = 10.0; // Arbitrary distance for look control
        double yawRad = Math.toRadians(yaw + 90.0); // Convert to radians and adjust for coordinate system
        double pitchRad = Math.toRadians(-pitch); // Negative pitch for correct direction
        
        double targetX = this.getX() + Math.cos(yawRad) * Math.cos(pitchRad) * lookDistance;
        double targetY = this.getEyeY() + Math.sin(pitchRad) * lookDistance;
        double targetZ = this.getZ() + Math.sin(yawRad) * Math.cos(pitchRad) * lookDistance;
        
        this.getLookControl().setLookAt(targetX, targetY, targetZ, 30.0F, 30.0F);
    }

    /**
     * Get the current direction vector that would be used for movement calculations.
     * This maintains compatibility with the existing moveTowards method.
     */
    public Vec3 getCurrentDirection() {
        if (!useRLAiming && watchGoal != null) {
            // Use the watchGoal's direction if RL aiming is disabled
            return watchGoal.getCurrentDirection();
        } else {
            // Calculate direction from current yaw rotation
            float yaw = this.getYRot();
            double yawRad = Math.toRadians(yaw + 90.0); // Adjust for coordinate system
            return new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad)).normalize();
        }
    }

    @Override
    public LivingEntity getTarget() {
        if (watchGoal != null) {
            return this.watchGoal.getTarget();
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();
        // Player attack mode agents are controlled by the same PythonRLController as training agents
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        this.agentId = AgentIdManager.assignId(this);
        RLOperatorRegistry.register(this);
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        AgentIdManager.releaseId(this);
        RLOperatorRegistry.unregister(this);
    }

    /**
     * Get the compact agent ID for binary protocol
     */
    public int getAgentId() {
        return agentId;
    }

    // @Override
    // public void travel(Vec3 travelVector) {
    //     this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
    //     super.travel(travelVector); // this applies zza/xxa/yya
    // }

    public void addDamageTaken(float amount) {
        damageTakenLastTick += amount;
    }

    public void addDamageDealt(float amount) {
        damageDealtLastTick += amount;
    }

    public void addDeath() {
        deaths++;
        deathsLastTick++;
    }

    public void addKill() {
        kills++;
        killsLastTick++;
    }

    public float getDamageTakenLastTick() {
        return damageTakenLastTick;
    }

    public float getDamageDealtLastTick() {
        return damageDealtLastTick;
    }

    public void clearTickDamageStats() {
        this.damageTakenLastTick = 0f;
        this.damageDealtLastTick = 0f;
        this.deathsLastTick = 0;
        this.killsLastTick = 0;
    }

    public int getDeaths() {
        return deaths;
    }

    public int getKills() {
        return kills;
    }

    public int getDeathsLastTick() {
        return deathsLastTick;
    }

    public int getKillsLastTick() {
        return killsLastTick;
    }

    /**
     * Set player attack mode - when enabled, this RLOperator will target the nearest player
     */
    public void setPlayerAttackMode(boolean enabled) {
        this.playerAttackMode = enabled;
        if (enabled && watchGoal != null) {
            // Force re-evaluation of target
            watchGoal.setPlayerTargetingMode(true);
        }
    }

    /**
     * Check if this RLOperator is in player attack mode
     */
    public boolean isPlayerAttackMode() {
        return playerAttackMode;
    }

    /**
     * Set whether this RLOperator should use RL-predicted aiming or auto-targeting.
     * 
     * @param useRLAiming If true, disable auto-targeting and use RL predictions for aim
     */
    public void setUseRLAiming(boolean useRLAiming) {
        this.useRLAiming = useRLAiming;
        
        // Dynamically add/remove the watch goal based on RL aiming mode
        if (useRLAiming) {
            // Remove auto-targeting goal when using RL aiming
            this.goalSelector.removeGoal(watchGoal);
        } else {
            // Add auto-targeting goal when not using RL aiming
            if (watchGoal != null) {
                this.goalSelector.addGoal(1, watchGoal);
            }
        }
    }

    /**
     * Check if this RLOperator is using RL-predicted aiming.
     */
    public boolean isUsingRLAiming() {
        return useRLAiming;
    }

    /**
     * Moves the entity in the direction relative to the current goal's direction vector.
     *
     * @param thetaDeg Relative angle in degrees (0 = forward, ±90 = strafe, 180 = backward)
     * @param speed    Desired movement speed (e.g., 0.0 to movement speed attribute)
     */
    public void moveTowards(float thetaDeg, float speed) {
        Vec3 baseDir = this.getCurrentDirection();

        // if (baseDir.lengthSqr() < 1e-6) {
        //     return;
        // }

        // Normalize and flatten the base direction
        Vec3 forward = new Vec3(baseDir.x, 0, baseDir.z).normalize();
        Vec3 right = new Vec3(forward.z, 0, -forward.x); // 90° rotated vector for strafe

        double thetaRad = Math.toRadians(thetaDeg);

        // Combine forward + right based on relative angle
        Vec3 moveVec = forward.scale(Math.cos(thetaRad)).add(right.scale(Math.sin(thetaRad))).normalize().scale(speed);

        // Preserve Y velocity
        this.setDeltaMovement(moveVec.x, this.getDeltaMovement().y, moveVec.z);
    }
}
