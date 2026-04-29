package com.example.lnbitstaptopay.printing

import android.content.Context
import android.util.Log

class NativeReceiptPrinter(
    context: Context,
    private val configRepository: PrinterConfigRepository = PrinterConfigRepository(context),
    private val bluetoothTransport: BluetoothPrinterTransport = BluetoothPrinterTransport(context, configRepository)
) {
    fun hasConfiguredPrinter(): Boolean = configRepository.getPrinterConfig() != null

    fun print(rawPayload: String): Boolean {
        val payload = ReceiptPrintPayload.fromRaw(rawPayload) ?: return false
        return print(payload)
    }

    fun print(payload: ReceiptPrintPayload): Boolean {
        val bytes = EscPosFormatter.buildReceiptPrint(
            printText = payload.printText,
            receiptType = payload.receiptType
        )
        return runCatching {
            bluetoothTransport.print(bytes)
            true
        }.onFailure { err ->
            Log.e("TPOS_PRINT", "Native receipt print failed", err)
        }.getOrDefault(false)
    }
}
