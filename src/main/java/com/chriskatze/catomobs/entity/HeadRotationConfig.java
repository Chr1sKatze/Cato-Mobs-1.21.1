package com.chriskatze.catomobs.entity;

/**
 * HeadRotationConfig is a configuration class that handles the head rotation behavior of mobs.
 * It defines the limits of head rotation based on different states like sleeping, attacking, or running.
 */
public class HeadRotationConfig {

    // ================================================================
    // Head rotation settings
    // ================================================================
    /**
     * Maximum pitch (up/down) rotation for the mob's head.
     */
    private final int maxHeadPitch;

    /**
     * Maximum yaw (left/right) rotation for the mob's head.
     */
    private final int maxHeadYaw;

    /**
     * Whether the mob's head should rotate while attacking.
     */
    private final boolean headTurnWhileAttacking;

    /**
     * Whether the mob's head should rotate while running.
     */
    private final boolean headTurnWhileRunning;

    // ================================================================
    // Constructor
    // ================================================================
    /**
     * Constructs a new HeadRotationConfig instance with the specified rotation limits and behaviors.
     *
     * @param maxHeadPitch            The maximum pitch (up/down) rotation for the head.
     * @param maxHeadYaw              The maximum yaw (left/right) rotation for the head.
     * @param headTurnWhileAttacking  Whether the head should turn while attacking.
     * @param headTurnWhileRunning    Whether the head should turn while running.
     */
    public HeadRotationConfig(int maxHeadPitch, int maxHeadYaw, boolean headTurnWhileAttacking, boolean headTurnWhileRunning) {
        this.maxHeadPitch = maxHeadPitch;
        this.maxHeadYaw = maxHeadYaw;
        this.headTurnWhileAttacking = headTurnWhileAttacking;
        this.headTurnWhileRunning = headTurnWhileRunning;
    }

    // ================================================================
    // Getters for Head Rotation Limits
    // ================================================================
    /**
     * Gets the maximum pitch (up/down) rotation for the head.
     * The pitch limit depends on whether the mob is sleeping, attacking, or running.
     *
     * @param isSleeping  Whether the mob is sleeping.
     * @param isAttacking Whether the mob is attacking.
     * @param isRunning   Whether the mob is running.
     * @return The maximum pitch rotation for the head.
     */
    public int getMaxHeadXRot(boolean isSleeping, boolean isAttacking, boolean isRunning) {
        // If the mob is sleeping, don't allow head rotation
        if (isSleeping) return 0;

        // If attacking and head rotation while attacking is disabled, don't allow head rotation
        if (isAttacking && !headTurnWhileAttacking) return 0;

        // If running and head rotation while running is disabled, don't allow head rotation
        if (isRunning && !headTurnWhileRunning) return 0;

        return maxHeadPitch; // Otherwise, return the max pitch defined
    }

    /**
     * Gets the maximum yaw (left/right) rotation for the head.
     * The yaw limit depends on whether the mob is sleeping, attacking, or running.
     *
     * @param isSleeping  Whether the mob is sleeping.
     * @param isAttacking Whether the mob is attacking.
     * @param isRunning   Whether the mob is running.
     * @return The maximum yaw rotation for the head.
     */
    public int getMaxHeadYRot(boolean isSleeping, boolean isAttacking, boolean isRunning) {
        // If the mob is sleeping, don't allow head rotation
        if (isSleeping) return 0;

        // If attacking and head rotation while attacking is disabled, don't allow head rotation
        if (isAttacking && !headTurnWhileAttacking) return 0;

        // If running and head rotation while running is disabled, don't allow head rotation
        if (isRunning && !headTurnWhileRunning) return 0;

        return maxHeadYaw; // Otherwise, return the max yaw defined
    }
}
