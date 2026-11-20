package app.aaps.core.ui.compose.camera

import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one part of the scanner that can be tested without a camera.
 *
 * Everything else in `IosCodeCamera.kt` needs a real device: the simulator has no camera, so the
 * capture session, the preview and the permission prompt can only be exercised on a phone.
 */
class CameraAccessTest {

    @Test
    fun `no camera on this device wins over any permission answer`() {
        // The simulator lands here, and so does a phone whose camera another app holds. Checked
        // first on purpose: being allowed to use a camera that is not there is not permission to
        // show a picture.
        assertEquals(CameraAccess.Unavailable, cameraAccess(hasDevice = false, status = AVAuthorizationStatusAuthorized))
        assertEquals(CameraAccess.Unavailable, cameraAccess(hasDevice = false, status = AVAuthorizationStatusDenied))
        assertEquals(CameraAccess.Unavailable, cameraAccess(hasDevice = false, status = AVAuthorizationStatusNotDetermined))
    }

    @Test
    fun `each permission answer maps to its own state`() {
        assertEquals(CameraAccess.Ready, cameraAccess(hasDevice = true, status = AVAuthorizationStatusAuthorized))
        assertEquals(CameraAccess.Denied, cameraAccess(hasDevice = true, status = AVAuthorizationStatusDenied))
        assertEquals(CameraAccess.Asking, cameraAccess(hasDevice = true, status = AVAuthorizationStatusNotDetermined))
    }

    @Test
    fun `restricted is told apart from denied because settings cannot mend it`() {
        // Parental controls or a management profile. The screen offers no "Open settings" button
        // here: the user cannot change it, so a button would be a control that does nothing.
        assertEquals(CameraAccess.Blocked, cameraAccess(hasDevice = true, status = AVAuthorizationStatusRestricted))
    }

    @Test
    fun `an unknown future status asks rather than giving up`() {
        // AVAuthorizationStatus is a plain Long in Kotlin/Native, not an enum, so a status added by
        // a later iOS reaches this branch. Asking is the recoverable choice: a refusal then lands
        // on Denied and the screen explains itself.
        assertEquals(CameraAccess.Asking, cameraAccess(hasDevice = true, status = 99L))
    }
}
