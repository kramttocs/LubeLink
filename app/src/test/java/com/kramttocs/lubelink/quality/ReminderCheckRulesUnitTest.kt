package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.ReminderCheckFailure
import com.kramttocs.lubelink.core.ReminderCheckRules
import com.kramttocs.lubelink.core.UnreadableResponseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OD-32: what a failed reminder check records and whether it is retried soon. */
class ReminderCheckRulesUnitTest {
    @Test fun eachFailureIsClassifiedByTypeAndStatus() {
        assertEquals(ReminderCheckFailure.REJECTED, ReminderCheckRules.classify(LubeLoggerApi.ApiException("x", 401)))
        assertEquals(ReminderCheckFailure.REJECTED, ReminderCheckRules.classify(LubeLoggerApi.ApiException("x", 403)))
        assertEquals(ReminderCheckFailure.SERVER, ReminderCheckRules.classify(LubeLoggerApi.ApiException("x", 500)))
        assertEquals(ReminderCheckFailure.UNREADABLE, ReminderCheckRules.classify(UnreadableResponseException("the reminders", "id")))
        assertEquals(ReminderCheckFailure.CONNECTION, ReminderCheckRules.classify(java.net.ConnectException()))
        assertEquals("A missing cause counts as a server fault", ReminderCheckFailure.SERVER, ReminderCheckRules.classify(null))
    }

    @Test fun onlyPassingFaultsAreRetriedAndAtMostThreeTimes() {
        for (failure in listOf(ReminderCheckFailure.REJECTED, ReminderCheckFailure.UNREADABLE))
            assertFalse("$failure is never retried", ReminderCheckRules.retries(failure, 0))
        for (failure in listOf(ReminderCheckFailure.SERVER, ReminderCheckFailure.CONNECTION)) {
            for (attempt in 0..2) assertTrue("$failure retried on attempt $attempt", ReminderCheckRules.retries(failure, attempt))
            assertFalse("$failure not retried after three retries", ReminderCheckRules.retries(failure, 3))
        }
    }
}
