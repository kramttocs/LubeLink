package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ReportDataset
import com.kramttocs.lubelink.core.ReportPeriod
import com.kramttocs.lubelink.core.ReportRateRules
import com.kramttocs.lubelink.core.ReportRules
import com.kramttocs.lubelink.core.ReportScope
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OD-52 and OD-55: Reports' four tiles, from records only (synthetic data). */
class ReportRateRulesUnitTest {
    private val today = LocalDate.of(2026, 10, 28)              // "This year" covers 301 days
    private val wagon = Vehicle(VehicleId(1), 2019, "Synthetic", "Wagon", "", false)
    private val truck = Vehicle(VehicleId(2), 2016, "Synthetic", "Truck", "", false)
    private val tractor = Vehicle(VehicleId(3), 2012, "Synthetic", "Tractor", "", useHours = true)
    private val all = listOf(wagon, truck, tractor)
    private var nextId = 1

    private fun rec(v: Vehicle, date: String, odometer: Long?, cost: String) =
        ServiceRecord(RecordId(nextId++), v.id, date, "Synthetic", odometer, cost, "", "", emptyList(), RecordKind.SERVICE)

    private fun rates(records: List<ServiceRecord>, scope: Vehicle?, period: ReportPeriod = ReportPeriod.THIS_YEAR,
                      vehicles: List<Vehicle> = all) =
        ReportRateRules.rates(ReportRules.expenses(ReportDataset(records), vehicles, vehicles.map { it.id }.toSet(),
            ReportScope(scope?.id), period, today), vehicles, scope != null, period, today)

    private val wagonYear = listOf(rec(wagon, "2026-03-01", 41_200, "100.00"), rec(wagon, "2026-10-25", 48_190, "52.10"),
        rec(wagon, "2026-06-30", 44_900, "39.99"))

    @Test fun oneVehicleUsesItsFirstAndLastReadingsByDateAndThePeriodsDays() {
        val r = rates(wagonYear, wagon)
        assertEquals(6_990L, r.distance)
        assertEquals(BigDecimal("0.03"), r.costPerDistance)                 // 192.09 / 6,990
        assertEquals(BigDecimal("0.64"), r.costPerDay)                      // 192.09 / 301
        assertEquals(BigDecimal("23.2226"), r.distancePerDay!!.setScale(4, RoundingMode.HALF_UP)) // 6,990 / 301
        assertEquals(listOf("Cost per mile" to "$0.03", "Cost per day" to "$0.64", "Distance" to "6,990 mi",
            "Per day" to "23 mi"), ReportRateRules.tiles(r))
        assertEquals(listOf(ReportRateRules.READINGS_NOTE), ReportRateRules.notes(r))
    }

    @Test fun withFewerThanTwoReadingsTheDistanceTilesSaySo() {
        val r = rates(listOf(rec(wagon, "2026-05-01", 45_000, "80.00"), rec(wagon, "2026-06-01", null, "20.00")), wagon)
        assertNull(r.distance); assertNull(r.costPerDistance); assertNull(r.distancePerDay)
        assertEquals(BigDecimal("0.33"), r.costPerDay)                      // 100.00 / 301
        assertEquals(ReportRateRules.NOT_ENOUGH, ReportRateRules.tiles(r)[0].second)
        assertEquals(ReportRateRules.NOT_ENOUGH, ReportRateRules.tiles(r)[2].second)
        assertFalse(r.readingsGoDown)
    }

    @Test fun aLaterReadingBelowAnEarlierOneIsNotADistanceAndTheTilesSaySo() {
        val r = rates(listOf(rec(wagon, "2026-08-01", 1_270, "10.00"), rec(wagon, "2026-09-01", 1_262, "10.00")), wagon)
        assertNull("A correction or reset never becomes a distance", r.distance)
        assertTrue(r.readingsGoDown)
        assertEquals(listOf("Cost per mile" to ReportRateRules.READINGS_GO_DOWN, "Cost per day" to "$0.07",
            "Distance" to ReportRateRules.READINGS_GO_DOWN, "Per day" to ReportRateRules.READINGS_GO_DOWN),
            ReportRateRules.tiles(r))
    }

    /** OD-61, OD-63: any reading lower than an earlier one, not only the last below the first. */
    @Test fun aDipBelowTheFirstThatRecoversIsReadingsGoDown() {
        val r = rates(listOf(rec(wagon, "2026-03-01", 1_000, "10.00"), rec(wagon, "2026-05-01", 900, "10.00"),
            rec(wagon, "2026-08-01", 1_200, "10.00")), wagon)
        assertNull(r.distance); assertNull(r.costPerDistance); assertNull(r.distancePerDay)
        assertTrue(r.readingsGoDown)
        assertEquals(ReportRateRules.READINGS_GO_DOWN, ReportRateRules.tiles(r)[2].second)
    }

    @Test fun aDropThatStaysAboveTheFirstIsReadingsGoDown() {
        val r = rates(listOf(rec(wagon, "2026-03-01", 1_000, "10.00"), rec(wagon, "2026-05-01", 1_500, "10.00"),
            rec(wagon, "2026-08-01", 1_200, "10.00")), wagon)
        assertNull(r.distance); assertTrue(r.readingsGoDown)
    }

    @Test fun anHourlyVehicleWithADropIsReadingsGoDownInHours() {
        val r = rates(listOf(rec(tractor, "2026-02-01", 1_100, "5"), rec(tractor, "2026-04-01", 1_050, "5"),
            rec(tractor, "2026-09-01", 1_250, "5")), tractor)
        assertTrue(r.hours); assertTrue(r.readingsGoDown)
        assertEquals("Hours" to ReportRateRules.READINGS_GO_DOWN, ReportRateRules.tiles(r)[2])
    }

    @Test fun allVehiclesLeaveOutAVehicleWithADropAndKeepTheOthers() {
        val dip = listOf(rec(truck, "2026-02-10", 98_400, "50.00"), rec(truck, "2026-05-01", 98_000, "50.00"),
            rec(truck, "2026-10-23", 102_690, "50.00"))
        val r = rates(wagonYear + dip, null, vehicles = listOf(wagon, truck))
        assertEquals("Only the wagon's miles", 6_990L, r.distance)
        assertEquals(BigDecimal("0.03"), r.costPerDistance)                 // the wagon's 192.09 / 6,990
        assertFalse("Another vehicle has a distance", r.readingsGoDown)
        val onlyDip = rates(dip, null, vehicles = listOf(wagon, truck))
        assertNull(onlyDip.distance); assertTrue(onlyDip.readingsGoDown)
    }

    /** On one day the order of readings is not known, so two readings on the same date never count as a drop. */
    @Test fun twoReadingsOnOneDateAreNotADrop() {
        val r = rates(listOf(rec(wagon, "2026-03-01", 1_000, "1"), rec(wagon, "2026-06-01", 1_300, "1"),
            rec(wagon, "2026-06-01", 1_250, "1"), rec(wagon, "2026-09-01", 1_400, "1")), wagon)
        assertEquals(400L, r.distance); assertFalse(r.readingsGoDown)
    }

    @Test fun theSameReadingTwiceIsNotEnoughReadingsNotReadingsGoDown() {
        val r = rates(listOf(rec(wagon, "2026-08-01", 1_270, "10.00"), rec(wagon, "2026-09-01", 1_270, "10.00")), wagon)
        assertNull(r.distance); assertFalse(r.readingsGoDown)
        assertEquals(ReportRateRules.NOT_ENOUGH, ReportRateRules.tiles(r)[2].second)
    }

    @Test fun allVehiclesSayReadingsGoDownOnlyWhenNoVehicleHasADistance() {
        val down = listOf(rec(truck, "2026-02-10", 98_400, "50.00"), rec(truck, "2026-10-23", 98_100, "50.00"))
        val onlyDown = rates(down, null, vehicles = listOf(wagon, truck))
        assertTrue(onlyDown.readingsGoDown)
        assertEquals(ReportRateRules.READINGS_GO_DOWN, ReportRateRules.tiles(onlyDown)[2].second)
        val withWagon = rates(wagonYear + down, null, vehicles = listOf(wagon, truck))
        assertEquals(6_990L, withWagon.distance); assertFalse(withWagon.readingsGoDown)
        val hourlyDown = rates(listOf(rec(tractor, "2026-02-01", 1_250, "5"), rec(tractor, "2026-09-01", 1_100, "5")), null)
        assertFalse("An hourly vehicle is left out of All vehicles' miles, so its readings are not counted",
            hourlyDown.readingsGoDown)
    }

    @Test fun perDayIsRoundedOnceFromTheUnroundedRate() {
        // 3,160 mi over 301 days is 10.498 a day: "10", where rounding to 10.5 first would show "11".
        val r = rates(listOf(rec(wagon, "2026-01-05", 1_000, "1"), rec(wagon, "2026-10-20", 4_160, "1")), wagon)
        assertEquals("Per day" to "10 mi", ReportRateRules.tiles(r)[3])
    }

    @Test fun allTimeCountsDaysFromTheFirstDatedRecord() {
        assertEquals(11L, ReportRateRules.days(ReportPeriod.ALL_TIME, listOf(rec(wagon, "2026-10-18", 1, "1")), today))
        assertNull(ReportRateRules.days(ReportPeriod.ALL_TIME, emptyList(), today))
        assertEquals(301L, ReportRateRules.days(ReportPeriod.THIS_YEAR, emptyList(), today))
    }

    @Test fun oneHourlyVehicleIsInHours() {
        val r = rates(listOf(rec(tractor, "2026-02-01", 1_100, "50.00"), rec(tractor, "2026-09-01", 1_250, "70.00")), tractor)
        assertTrue(r.hours)
        assertEquals(listOf("Cost per hour" to "$0.80", "Cost per day" to "$0.40", "Hours" to "150 hr",
            "Per day" to "0.5 hr"), ReportRateRules.tiles(r))
        assertFalse("One vehicle: nothing is left out", r.hourlyLeftOut)
    }

    @Test fun allVehiclesAddOnlyTheMileageVehiclesWithADistanceAndSaySoWhenHoursAreLeftOut() {
        val records = wagonYear + listOf(rec(truck, "2026-02-10", 98_400, "649.00"), rec(truck, "2026-10-23", 102_690, "88.34"),
            rec(tractor, "2026-02-01", 1_100, "500.00"), rec(tractor, "2026-09-01", 1_250, "70.00"))
        val r = rates(records, null)
        assertFalse(r.hours)
        assertEquals(6_990L + 4_290L, r.distance)
        assertEquals(BigDecimal("0.08"), r.costPerDistance)                 // (192.09 + 737.34) / 11,280
        assertEquals(BigDecimal("4.98"), r.costPerDay)                      // every vehicle: 1,499.43 / 301
        assertTrue(r.hourlyLeftOut)
        assertEquals(listOf(ReportRateRules.READINGS_NOTE, ReportRateRules.HOURLY_NOTE), ReportRateRules.notes(r))
    }

    @Test fun aMileageVehicleWithoutADistanceAddsNeitherMilesNorCostPerMile() {
        val records = wagonYear + listOf(rec(truck, "2026-02-10", 98_400, "900.00"))
        val r = rates(records, null, vehicles = listOf(wagon, truck))
        assertEquals(6_990L, r.distance)
        assertEquals(BigDecimal("0.03"), r.costPerDistance)                 // the wagon's 192.09 only
        assertFalse("No hourly vehicle in the report: no line", r.hourlyLeftOut)
        assertEquals(listOf(ReportRateRules.READINGS_NOTE), ReportRateRules.notes(r))
    }

    @Test fun perDayIsWholeFromTenAndOneDecimalBelow() {
        assertEquals("23", ReportRateRules.perDay(BigDecimal("23.2")))
        assertEquals("1,250", ReportRateRules.perDay(BigDecimal("1249.6")))
        assertEquals("0.5", ReportRateRules.perDay(BigDecimal("0.48")))
        assertEquals("3", ReportRateRules.perDay(BigDecimal("3.0")))
    }
}
