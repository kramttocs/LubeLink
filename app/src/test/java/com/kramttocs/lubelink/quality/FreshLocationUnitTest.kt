package com.kramttocs.lubelink.quality

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kramttocs.lubelink.core.FuelStationLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** With no recent fix, Nearby asks for a new one and uses it instead of failing at once (LL-07). The minimum
 * supported version is Android 11 (OD-81), which has the platform call. */
@RunWith(RobolectricTestRunner::class)
class FreshLocationUnitTest {
    @Test
    fun withNoRecentFixANewOneIsRequestedAndUsed() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        val fix = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 40.0; longitude = -90.0; accuracy = 20f; time = System.currentTimeMillis()
        }
        runBlocking {
            val result = async(Dispatchers.Default) { FuelStationLookup.freshLocation(context) }
            // Answer once the app has asked; the app's callback runs on the main thread, which this test drives.
            val deadline = System.currentTimeMillis() + 4_000
            while (!result.isCompleted && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                shadow.simulateLocation(fix.apply { time = System.currentTimeMillis() })
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            val location = result.await()
            assertNotNull("no fresh location on API ${android.os.Build.VERSION.SDK_INT}", location)
            assertEquals(40.0, location!!.latitude, 0.0)
        }
    }
}
