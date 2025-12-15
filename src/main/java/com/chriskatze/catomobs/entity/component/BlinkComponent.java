package com.chriskatze.catomobs.entity.component;

import net.minecraft.util.RandomSource;

/**
 * BlinkComponent
 *
 * Client-only cosmetic helper for random eye blinking.
 *
 * Responsibilities:
 * - manage blink timing & duration
 * - provide one-shot animation reset signal
 *
 * Non-responsibilities:
 * - no rendering
 * - no GeckoLib controller logic
 * - no server-side logic
 */
public final class BlinkComponent {

    // ------------------------------------------------------------
    // Configuration (easy to tune or expose later)
    // ------------------------------------------------------------

    /** Min ticks between blinks (inclusive). */
    private final int minCooldownTicks;

    /** Additional random ticks added to min cooldown. */
    private final int randomCooldownTicks;

    /** Duration of a blink in ticks. */
    private final int blinkDurationTicks;

    // ------------------------------------------------------------
    // Runtime state
    // ------------------------------------------------------------

    private int blinkCooldown;
    private int blinkTicksRemaining;

    private boolean blinking;
    private boolean blinkJustStarted;

    private final RandomSource random;

    // ------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------

    public BlinkComponent(RandomSource random) {
        this(random, 20, 40, 6);
    }

    public BlinkComponent(
            RandomSource random,
            int minCooldownTicks,
            int randomCooldownTicks,
            int blinkDurationTicks
    ) {
        this.random = random;
        this.minCooldownTicks = Math.max(1, minCooldownTicks);
        this.randomCooldownTicks = Math.max(0, randomCooldownTicks);
        this.blinkDurationTicks = Math.max(1, blinkDurationTicks);

        resetCooldown();
    }

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    /** Tick once per client tick. */
    public void tick() {
        if (blinking) {
            if (--blinkTicksRemaining <= 0) {
                blinking = false;
            }
            return;
        }

        if (--blinkCooldown <= 0) {
            startBlink();
        }
    }

    /** Whether the blink animation should currently be playing. */
    public boolean isBlinking() {
        return blinking;
    }

    /**
     * One-shot flag consumed by GeckoLib controller
     * to force animation reset exactly once.
     */
    public boolean consumeBlinkJustStarted() {
        if (blinkJustStarted) {
            blinkJustStarted = false;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------

    private void resetCooldown() {
        blinkCooldown = minCooldownTicks + random.nextInt(randomCooldownTicks + 1);
    }

    private void startBlink() {
        blinking = true;
        blinkJustStarted = true;
        blinkTicksRemaining = blinkDurationTicks;
        resetCooldown();
    }
}