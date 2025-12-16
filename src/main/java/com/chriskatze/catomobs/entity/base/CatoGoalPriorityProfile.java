package com.chriskatze.catomobs.entity.base;

/**
 * Centralized, per-mob configurable AI goal priorities.
 *
 * Minecraft AI uses two selectors:
 * - goalSelector   = "actions" (move, wander, attack, sleep, look, etc.)
 * - targetSelector = "who to target" (pick enemies, retaliate, etc.)
 *
 * Each goal is registered with a PRIORITY NUMBER:
 * - Lower number = higher priority (runs first / can block lower-priority goals)
 * - Higher number = lower priority (more "background" behavior)
 *
 * This profile controls BOTH:
 * 1) The numeric priorities for each goal
 * 2) Whether certain groups of goals are enabled at all
 *
 * Typical usage:
 * - Base mob returns defaults:
 *     protected CatoGoalPriorityProfile getGoalPriorities() {
 *         return CatoGoalPriorityProfile.defaults();
 *     }
 * - Specific mob tweaks them:
 *     protected CatoGoalPriorityProfile getGoalPriorities() {
 *         return CatoGoalPriorityProfile.defaults().copy();
 *     }
 *   ...then change fields (enableWander=false, or wander=12, etc.)
 */
public class CatoGoalPriorityProfile {

    // ------------------------------------------------------------
    // Enable / disable goal groups
    // ------------------------------------------------------------
    /**
     * Enables the sleep system (CatoSleepGoal + optional CatoSleepSearchGoal).
     * If false, sleeping goals are never registered.
     */
    public boolean enableSleep = true;

    /**
     * Enables "find a roofed spot to sleep" behavior (CatoSleepSearchGoal),
     * but only if sleeping itself is enabled.
     */
    public boolean enableSleepSearch = true;

    /**
     * Enables cosmetic "look around" goals:
     * - LookAtPlayerGoal
     * - RandomLookAroundGoal
     */
    public boolean enableLookGoals = true;

    /**
     * Enables idle roaming/wandering (CatoWanderGoal or other movement goals).
     * Useful to disable for stationary mobs (e.g., statues, turrets, etc.).
     */
    public boolean enableWander = true;

    /**
     * Enables TemptGoal if the mob provides a temptItem.
     * If false, even a valid tempt item won't register TemptGoal.
     */
    public boolean enableTempt = true;

    /**
     * Enables BreedGoal + FollowParentGoal (only if the mob has canBreed=true).
     */
    public boolean enableBreeding = true;

    // ------------------------------------------------------------
    // goalSelector priorities (actions / movement / behavior)
    // ------------------------------------------------------------
    /**
     * FloatGoal is usually priority 0 so drowning avoidance always wins.
     */
    public int floatGoal = 0;

    /**
     * Sleep "lock" goal: high priority so it blocks MOVE/LOOK/JUMP while sleeping.
     * This is what freezes the mob (no head turning, no wandering, etc.).
     */
    public int sleepLock = 1;

    /**
     * Sleep search goal: tries to navigate to a roofed spot before sleeping.
     * Slightly lower than sleepLock, because sleepLock should dominate once sleeping.
     */
    public int sleepSearch = 2;

    /**
     * Cosmetic look-at-player. Usually above random look, but below sleep.
     */
    public int lookAtPlayer = 11;

    /**
     * Cosmetic random head movement (idle scanning).
     */
    public int randomLook = 10;

    /**
     * TemptGoal: "follow the player holding X item".
     * Generally higher priority than wandering so it overrides idle movement.
     */
    public int tempt = 5;

    /**
     * Melee attack/chase behavior.
     * Depending on your design you might set this even higher than tempt.
     */
    public int meleeAttack = 3;

    /**
     * Breeding behaviors.
     * These are often lower than combat/tempt, higher than wandering.
     */
    public int breed = 7;

    /**
     * Child-follow-parent behavior (baby mobs).
     */
    public int followParent = 8;

    /**
     * Default idle movement should be low priority so it loses to most other goals.
     */
    public int wander = 9;

    // ------------------------------------------------------------
    // targetSelector priorities (target acquisition / hostility)
    // ------------------------------------------------------------
    /**
     * Retaliation target selection: "if I get hurt, target the attacker".
     * Typically very high priority.
     */
    public int targetHurtBy = 1;

    /**
     * "Find nearest player to attack" target selection (hostile mobs).
     * Usually below hurt-by, because retaliation should win.
     */
    public int targetNearestPlayer = 2;

    // ------------------------------------------------------------
    // Factory helpers
    // ------------------------------------------------------------
    /**
     * Returns a new profile with default values.
     * (Convenience method so callers don't have to write 'new CatoGoalPriorityProfile()'.)
     */
    public static CatoGoalPriorityProfile defaults() {
        return new CatoGoalPriorityProfile();
    }

    /**
     * Creates a copy of this profile.
     *
     * Why copy matters:
     * - You usually want each mob to have its own independent profile instance,
     *   so tweaking one mob doesn't accidentally affect others (especially if you later
     *   add shared/static profiles or reuse instances).
     */
    public CatoGoalPriorityProfile copy() {
        CatoGoalPriorityProfile p = new CatoGoalPriorityProfile();

        // Copy enable flags
        p.enableSleep = this.enableSleep;
        p.enableSleepSearch = this.enableSleepSearch;
        p.enableLookGoals = this.enableLookGoals;
        p.enableWander = this.enableWander;
        p.enableTempt = this.enableTempt;
        p.enableBreeding = this.enableBreeding;

        // Copy goalSelector priorities
        p.floatGoal = this.floatGoal;
        p.sleepLock = this.sleepLock;
        p.sleepSearch = this.sleepSearch;
        p.lookAtPlayer = this.lookAtPlayer;
        p.randomLook = this.randomLook;
        p.tempt = this.tempt;
        p.meleeAttack = this.meleeAttack;
        p.breed = this.breed;
        p.followParent = this.followParent;
        p.wander = this.wander;

        // Copy targetSelector priorities
        p.targetHurtBy = this.targetHurtBy;
        p.targetNearestPlayer = this.targetNearestPlayer;

        return p;
    }
}
