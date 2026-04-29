package com.example.lnbitstaptopay.printing

import android.content.Context

class PrinterConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("tpos_prefs", Context.MODE_PRIVATE)

    data class PrinterConfig(
        val mode: String,
        val name: String,
        val address: String
    )

    fun getPrinterConfig(): PrinterConfig? {
        val mode = prefs.getString(KEY_MODE, MODE_NONE).orEmpty()
        val address = prefs.getString(KEY_ADDRESS, "").orEmpty()
        if (mode != MODE_BLUETOOTH || address.isBlank()) return null
        return PrinterConfig(
            mode = mode,
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            address = address
        )
    }

    fun saveBluetoothPrinter(name: String, address: String) {
        prefs.edit()
            .putString(KEY_MODE, MODE_BLUETOOTH)
            .putString(KEY_NAME, name)
            .putString(KEY_ADDRESS, address)
            .apply()
    }

    fun clearPrinter() {
        prefs.edit()
            .remove(KEY_MODE)
            .remove(KEY_NAME)
            .remove(KEY_ADDRESS)
            .apply()
    }

    companion object {
        const val MODE_NONE = "none"
        const val MODE_BLUETOOTH = "bluetooth"

        private const val KEY_MODE = "printerMode"
        private const val KEY_NAME = "printerName"
        private const val KEY_ADDRESS = "printerAddress"
    }
}
