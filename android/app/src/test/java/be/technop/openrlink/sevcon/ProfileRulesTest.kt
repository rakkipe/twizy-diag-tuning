package be.technop.openrlink.sevcon

import org.junit.Assert.*
import org.junit.Test

class ProfileRulesTest {
    private val stock get() = TuningProfiles.byName("STOCK")!!

    @Test fun allBundledProfilesHaveConsistentCurves() {
        TuningProfiles.LIST.forEach { assertEquals(it.name, emptyList<String>(), ProfileRules.errors(it)) }
    }

    @Test fun originalProfilesKeepTheirOriginalParametersAndRamps() {
        assertEquals(listOf(
            TuningProfile("STOCK", -1, -1, 100, 100, 100, 100, 100, 18, 18),
            TuningProfile("ECO", 55, 60, 70, 70, 70, 100, 70, 40, 40),
            TuningProfile("STAD", 45, 50, 90, 90, 90, 100, 80, 40, 50),
            TuningProfile("SNELWEG", 90, 95, 110, 120, 120, 110, 100, 18, 25),
            TuningProfile("RACE-LITE", 90, 95, 150, 140, 140, 110, 100, 20, 35)
        ), TuningProfiles.LIST.take(5))
    }

    @Test fun sprintDoesNotRaiseCurrentTorqueOrSpeedAboveExistingRaceProfile() {
        val sprint = TuningProfiles.byName("DRAGRACE")!!
        val race = TuningProfiles.byName("RACE-LITE")!!
        assertTrue(sprint.current <= race.current)
        assertTrue(sprint.torque <= race.torque)
        assertTrue(sprint.speed <= race.speed)
        assertTrue(sprint.rampAccel > stock.rampAccel)
    }

    @Test fun copyingAndEditingDoesNotMutateTheBundledProfile() {
        val copy = stock.copy(name = "Mijn profiel", torque = 120, rampAccel = 60)
        assertEquals(100, stock.torque)
        assertEquals(25, stock.rampAccel)
        assertEquals(120, copy.torque)
        assertTrue(ProfileRules.errors(copy).isEmpty())
    }

    @Test fun factoryWarningCannotSilentlyFallBelowCustomSpeed() {
        assertTrue(ProfileRules.errors(stock.copy(speed = 100)).isNotEmpty())
        assertTrue(ProfileRules.errors(stock.copy(speed = 100, warn = 110)).isEmpty())
    }

    @Test fun descendingPowerCurveIsRejectedEvenWhenIndividualSlidersAreInRange() {
        val impossible = stock.copy(speed = 6, warn = 10, torque = 10, powerLow = 140)
        assertTrue(ProfileRules.errors(impossible).any { it.contains("vermogenscurve") })
    }

    @Test fun onlySpeedFieldsAllowFactorySentinel() {
        assertTrue(ProfileRules.errors(stock).isEmpty())
        assertTrue(ProfileRules.errors(stock.copy(current = -1)).isNotEmpty())
        assertTrue(ProfileRules.errors(stock.copy(torque = -1)).isNotEmpty())
    }

    @Test fun invalidLimitsAndNamesAreRejected() {
        listOf(stock.copy(current = 121), stock.copy(rampStart = 0), stock.copy(rampAccel = 101),
            stock.copy(neutral = -1), stock.copy(brake = 101), stock.copy(name = " "),
            stock.copy(name = "naam\nregel"), stock.copy(name = "a".repeat(41)))
            .forEach { assertTrue(it.toString(), ProfileRules.errors(it).isNotEmpty()) }
    }
}
