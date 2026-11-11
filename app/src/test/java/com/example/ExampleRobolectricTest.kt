package com.example

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Omni", appName)
    }

    @Test
    fun `launch MainActivity successfully without crash`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity)
            }
        }
    }

    @Test
    fun `verify managed store schedule configuration`() {
        val viewModel = com.example.ui.MainViewModel()
        assertNotNull(viewModel.managedStore.value)

        // The public real-merchant directory intentionally starts empty until a
        // live catalog is loaded. Schedule editing belongs to the managed store,
        // so this test must not depend on a demo pharmacy in the public directory.
        viewModel.updateManagedStoreSchedule(
            openTime = "07:30",
            closeTime = "22:00",
            weekendHoursText = "Sáb y Dom 08:00 - 13:00",
            is24Hours = false,
            isOnDuty = true,
            openOnHolidays = true,
            scheduleNotes = "Timbre nocturno habilitado",
            isOpenNow = true
        )

        val updated = viewModel.managedStore.value
        assertEquals("07:30", updated.openTime)
        assertEquals("22:00", updated.closeTime)
        assertEquals(true, updated.isOnDuty)
        assertEquals(true, updated.openOnHolidays)
        assertEquals(true, updated.isEffectivelyOpen)
    }
}
