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
    public void walkingIsNotSlowedByTrafficButByRain() {
        double dry = EtaModel.onlineFactor(CostModel.Mode.WALK, 1.0, 0, Double.NaN);
        double wet = EtaModel.onlineFactor(CostModel.Mode.WALK, 1.0, 2, Double.NaN);
        assertEquals(1.0, dry, 1e-9);
        assertTrue(wet > 1.2);
    }

    @Test
    public void rushHourSlowsCarsAboutTwofold() {
        double rush = EtaModel.onlineFactor(CostModel.Mode.CAR, 1.0, 0, Double.NaN);
        double night = EtaModel.onlineFactor(CostModel.Mode.CAR, 0.0, 0, Double.NaN);
        assertEquals(2.0, rush, 0.05);
        assertEquals(1.0, night, 0.01);
        assertTrue(EtaModel.onlineFactor(CostModel.Mode.CYCLE, 1.0, 0, Double.NaN) < rush); // bikes slip through
    }

    @Test
    public void measuredSlowdownReplacesTheEstimate() {
        // measured: roads take 1.6x as long as free flow, even though the clock says a quiet hour
        double f = EtaModel.onlineFactor(CostModel.Mode.CAR, 0.0, 0, 1.6);
        assertEquals(1.6, f, 1e-9);
    }

    @Test
    public void junctionDelaysAreOrderedByMode() {
        assertTrue(EtaModel.junctionDelay(CostModel.Mode.CAR) > EtaModel.junctionDelay(CostModel.Mode.CYCLE));
        assertTrue(EtaModel.junctionDelay(CostModel.Mode.CYCLE) > EtaModel.junctionDelay(CostModel.Mode.WALK));
    }

    @Test
    public void liveMeasurementsDriveTheCityLevel() {
        List<double[]> heavy = new ArrayList<>();
        heavy.add(new double[]{22.8, 89.5, 0.75});
        heavy.add(new double[]{22.9, 89.6, 0.70});
        TrafficModel live = TrafficModel.forTime(0, java.util.TimeZone.getTimeZone("UTC")).withLive(heavy);
        assertTrue(live.isLive());
        assertEquals(TrafficModel.HEAVY, live.cityLevel());

        List<double[]> light = new ArrayList<>();
        light.add(new double[]{22.8, 89.5, 0.05});
        TrafficModel quiet = TrafficModel.rushHour().withLive(light); // rush hour on the clock, but the road is empty
        assertEquals(TrafficModel.FREE, quiet.cityLevel());
    }
}
