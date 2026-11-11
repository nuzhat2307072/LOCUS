package com.locus.app.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Arrival time: calibration against the online router, traffic and rain factors, live traffic colours. */
public class EtaModelTest {
    @Test
    public void calibrationScalesGraphTimeToTheReference() {
        assertEquals(1.25, EtaModel.calibrationScale(600, 480), 1e-9);
        assertEquals(1.0, EtaModel.calibrationScale(0, 480), 1e-9);   // no reference: unchanged
        assertEquals(1.0, EtaModel.calibrationScale(600, 0), 1e-9);
        assertEquals(1.9, EtaModel.calibrationScale(6000, 480), 1e-9); // absurd answers are clamped
        assertEquals(0.6, EtaModel.calibrationScale(10, 480), 1e-9);
    }

    @Test
    public void rainSlowsWalkingMostAndCarsLeast() {
        assertEquals(1.0, CostModel.rainSlowdown(CostModel.Mode.WALK, 0), 1e-9);
        assertTrue(CostModel.rainSlowdown(CostModel.Mode.WALK, 2) > 1.2);
        assertTrue(CostModel.rainSlowdown(CostModel.Mode.CYCLE, 1) > CostModel.rainSlowdown(CostModel.Mode.CAR, 1));
        assertTrue(CostModel.rainSlowdown(CostModel.Mode.CAR, 2) > CostModel.rainSlowdown(CostModel.Mode.CAR, 1));
    }

    @Test
    public void junctionDelaysAreOrderedByMode() {
        assertTrue(EtaModel.junctionDelay(CostModel.Mode.CAR) > EtaModel.junctionDelay(CostModel.Mode.CYCLE));
        assertTrue(EtaModel.junctionDelay(CostModel.Mode.CYCLE) > EtaModel.junctionDelay(CostModel.Mode.WALK));
    }
}
