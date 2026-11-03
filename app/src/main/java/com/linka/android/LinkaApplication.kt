package com.linka.android

import android.app.Application
import com.linka.android.core.call.NearbyCallController
import com.linka.android.core.identity.LocalDeviceIdentity
import com.linka.android.core.nearby.NearbyDirectLink

/**
 * Owns process-wide LINKA identity, radio link and call controller so activity
 * recreation does not tear down a live peer connection or voice session.
 */
class LinkaApplication : Application() {
    lateinit var identity: LocalDeviceIdentity
        private set

    lateinit var nearbyLink: NearbyDirectLink
        private set

    lateinit var callController: NearbyCallController
        private set

    override fun onCreate() {
        super.onCreate()
        identity = LocalDeviceIdentity.load(this)
        nearbyLink = NearbyDirectLink(this, "LINKA ${identity.shortId}")
        callController = NearbyCallController(this, identity.deviceId, nearbyLink)
    }
}
