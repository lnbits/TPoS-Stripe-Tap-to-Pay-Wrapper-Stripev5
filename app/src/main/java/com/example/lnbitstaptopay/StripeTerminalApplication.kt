package com.example.lnbitstaptopay

import android.app.Application
import com.stripe.stripeterminal.TerminalApplicationDelegate
import com.stripe.stripeterminal.taptopay.TapToPay

class StripeTerminalApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Tap to Pay on Android runs in a dedicated process. Stripe recommends
        // skipping normal app initialization inside that process.
        if (TapToPay.isInTapToPayProcess()) return

        TerminalApplicationDelegate.onCreate(this)
    }
}
