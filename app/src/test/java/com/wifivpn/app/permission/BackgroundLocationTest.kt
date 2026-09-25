package com.wifivpn.app.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.wifivpn.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundLocationTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val issueText: String
        get() = app.getString(R.string.perm_issue_background_location)

    @Test
    fun notGranted_requestsPermission() {
        assertFalse(BackgroundLocation.isGranted(app))
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            BackgroundLocation.permissionToRequest(app)
        )
    }

    @Test
    fun granted_nothingToRequest() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(BackgroundLocation.isGranted(app))
        assertNull(BackgroundLocation.permissionToRequest(app))
    }

    @Test
    @Config(sdk = [28])
    fun beforeApi29_notASeparatePermission() {
        assertTrue(BackgroundLocation.isGranted(app))
        assertNull(BackgroundLocation.permissionToRequest(app))
    }

    @Test
    fun weeklyCheck_flagsOnlyWhenAutoStartIsOn() {
        assertTrue(issueText in PermissionStatusChecker.missingIssues(app, autoStartEnabled = true))
        assertFalse(issueText in PermissionStatusChecker.missingIssues(app, autoStartEnabled = false))
    }

    @Test
    fun weeklyCheck_silentOnceGranted() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertFalse(issueText in PermissionStatusChecker.missingIssues(app, autoStartEnabled = true))
    }
}
