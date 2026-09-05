package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimClockTest {

    @Test
    void oneSecondAtNormalSpeedRunsTheConfiguredTickRate() {
        SimClock clock = new SimClock();
        int ticks = 0;
        // Fed as 60 render frames, which is the real usage pattern - the total
        // must come out right regardless of how the time is chopped up.
        for (int i = 0; i < 60; i++) {
            ticks += clock.advance(1.0 / 60.0);
        }
        assertEquals(SimConfig.TICKS_PER_SECOND, ticks,
            "one second of frames should produce exactly one second of ticks");
    }

    @Test
    void speedMultipliesTickRate() {
        for (int speed : new int[] {1, 2, 5}) {
            SimClock clock = new SimClock();
            clock.setSpeed(speed);
            int ticks = 0;
            for (int i = 0; i < 60; i++) {
                ticks += clock.advance(1.0 / 60.0);
            }
            assertEquals(SimConfig.TICKS_PER_SECOND * speed, ticks, "wrong tick count at speed " + speed);
        }
    }

    @Test
    void pauseProducesNoTicks() {
        SimClock clock = new SimClock();
        clock.setSpeed(0);
        assertTrue(clock.isPaused());
        int ticks = 0;
        for (int i = 0; i < 120; i++) {
            ticks += clock.advance(1.0 / 60.0);
        }
        assertEquals(0, ticks, "a paused clock must never tick");
    }

    @Test
    void unpausingDoesNotFireABacklogOfTicks() {
        SimClock clock = new SimClock();
        // Partially fill the accumulator, then pause and wait a long time.
        clock.advance(0.09);
        clock.setSpeed(0);
        clock.advance(30.0);
        clock.setSpeed(1);
        assertEquals(0, clock.advance(0.0), "no time has passed since unpausing");
        assertEquals(1, clock.advance(0.1), "should resume cleanly at the normal rate");
    }

    @Test
    void longFrameIsCappedRatherThanSpiralling() {
        SimClock clock = new SimClock();
        // A 10-second stall would be 100 ticks at 10/s; the cap must hold.
        int ticks = clock.advance(10.0);
        assertEquals(SimConfig.MAX_TICKS_PER_FRAME, ticks, "catch-up must be capped");
        // Crucially, the discarded backlog must not resurface next frame.
        assertEquals(0, clock.advance(0.0));
        assertEquals(1, clock.advance(SimConfig.SECONDS_PER_TICK));
    }

    @Test
    void fractionalTimeAccumulatesInsteadOfBeingLost() {
        SimClock clock = new SimClock();
        // Each call is well under one tick; none of them individually fires,
        // but the leftovers must add up rather than being rounded away.
        int ticks = 0;
        for (int i = 0; i < 10; i++) {
            ticks += clock.advance(0.03);
        }
        assertEquals(3, ticks, "0.3s at 10 ticks/s should be 3 ticks");
    }

    @Test
    void zeroAndNegativeDeltasAreIgnored() {
        SimClock clock = new SimClock();
        assertEquals(0, clock.advance(0.0));
        assertEquals(0, clock.advance(-5.0));
        assertFalse(clock.isPaused(), "a negative delta should not change the speed");
    }

    @Test
    void speedNeverGoesNegative() {
        SimClock clock = new SimClock();
        clock.setSpeed(-3);
        assertEquals(0, clock.getSpeed());
        assertEquals(0, clock.advance(1.0));
    }
}
