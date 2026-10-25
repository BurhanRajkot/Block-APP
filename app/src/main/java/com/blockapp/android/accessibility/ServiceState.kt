package com.blockapp.android.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether AppBlockAccessibilityService is bound and running in this process right now.
 *
 * "Switched on in Settings" and "running" are different things, and telling them apart is what
 * this exists for. The switch lives in Settings.Secure and stays on when the service crashes or
 * the system stops rebinding it: Samsung then shows "Not working" under the switch, a Pixel
 * shows "This service is malfunctioning", and the app used to read the switch alone and say
 * "Protected" over a service that blocked nothing. The other way round happened too: on some
 * builds the Secure string lagged behind a service that was already connected, and the app
 * said "Accessibility is off" right after the user had turned it on.
 *
 * The service runs in the same process as the UI, so this flag is exact for this process. It
 * starts false on every process start and turns true within a moment once the system rebinds,
 * which is why the UI waits a short grace period before calling a switched-on service "not
 * running" (see DeviceAdminHelper.accessibilityState and its callers).
 *
 * Only AppBlockAccessibilityService writes it.
 */
object ServiceState {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    internal fun markConnected(value: Boolean) {
        _connected.value = value
    }
}
