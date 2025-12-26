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
 */
public class CatoGoalPriorityProfile {

    // ------------------------------------------------------------
    // Enable / disable goal groups
    // ------------------------------------------------------------

    /** Enables the sleep system (CatoSleepGoal + optional CatoSleepSearchGoal). */
    public boolean enableSleep = true;

    /** Enables "find a roofed spot to sleep" behavior (CatoSleepSearchGoal). */
    public boolean enableSleepSearch = true;

    /** Enables cosmetic "look around" goals. */
    public boolean enableLookGoals = true;

    /** Enables idle roaming/wandering. */
    public boolean enableWander = true;

    /** Enables TemptGoal if the mob provides a temptItem. */
    public boolean enableTempt = true;

    /** Enables BreedGoal + FollowParentGoal (only if the mob has canBreed=true). */
    public boolean enableBreeding = true;

    /** Enables flee/panic behavior (CatoFleeGoal). */
    public boolean enableFlee = true;

    // ------------------------------------------------------------
    // goalSelector priorities (actions / movement / behavior)
    // ------------------------------------------------------------

    /** FloatGoal is usually priority 0 so drowning avoidance always wins. */
    public int floatGoal = 0;

    /**
     * Flee / panic goal (should override combat + wander).
     * Must be higher priority than meleeAttack.
     */
    public int flee = 1;

    /** Sleep lock goal (blocks movement/AI while sleeping). */
    public int sleepLock = 2;

    /** Sleep search goal (navigate to roofed spot before sleeping). */
    public int sleepSearch = 3;

    /** Melee attack/chase behavior. */
    public int meleeAttack = 4;

    /** TemptGoal: follow the player holding X item. */
    public int tempt = 5;

    /** Breeding behaviors. */
    public int breed = 7;

    /** Child-follow-parent behavior (baby mobs). */
    public int followParent = 8;

    /** Rain shelter behavior (seek roof while raining). */
    public int rainShelter = 9;

    /** Optional "fun swim" behavior (go swim briefly, then exit). */
    public int funSwim = 10;

    /** Default idle movement should be low priority. */
    public int wander = 11;

    /** Cosmetic random head movement (idle scanning). */
    public int randomLook = 12;

    /** Cosmetic look-at-player. */
    public int lookAtPlayer = 13;

    // ------------------------------------------------------------
    // targetSelector priorities (target acquisition / hostility)
    // ------------------------------------------------------------

    /** Retaliation target selection: "if I get hurt, target the attacker". */
    public int targetHurtBy = 1;

    /** "Find nearest player to attack" target selection (hostile mobs). */
    public int targetNearestPlayer = 2;

    // ------------------------------------------------------------
    // Factory helpers
    // ------------------------------------------------------------

    public static CatoGoalPriorityProfile defaults() {
        return new CatoGoalPriorityProfile();
    }

    public CatoGoalPriorityProfile copy() {
        CatoGoalPriorityProfile p = new CatoGoalPriorityProfile();

        // Copy enable flags
        p.enableSleep = this.enableSleep;
        p.enableSleepSearch = this.enableSleepSearch;
        p.enableLookGoals = this.enableLookGoals;
        p.enableWander = this.enableWander;
        p.enableTempt = this.enableTempt;
        p.enableBreeding = this.enableBreeding;
        p.enableFlee = this.enableFlee;

        // Copy goalSelector priorities
        p.floatGoal = this.floatGoal;
        p.flee = this.flee;
        p.sleepLock = this.sleepLock;
        p.sleepSearch = this.sleepSearch;
        p.meleeAttack = this.meleeAttack;
        p.tempt = this.tempt;
        p.breed = this.breed;
        p.followParent = this.followParent;
        p.rainShelter = this.rainShelter;
        p.funSwim = this.funSwim;
        p.wander = this.wander;
        p.randomLook = this.randomLook;
        p.lookAtPlayer = this.lookAtPlayer;

        // Copy targetSelector priorities
        p.targetHurtBy = this.targetHurtBy;
        p.targetNearestPlayer = this.targetNearestPlayer;

        return p;
    }
}
