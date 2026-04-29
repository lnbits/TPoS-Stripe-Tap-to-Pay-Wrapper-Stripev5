package com.example.lnbitstaptopay.printing

import org.json.JSONObject

data class ReceiptPrintPayload(
    val printText: String,
    val receiptType: String,
    val paymentHash: String
) {
    companion object {
        fun fromRaw(raw: String): ReceiptPrintPayload? {
            val document = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val printText = document.optString("print_text").trim()
            if (printText.isBlank()) return null
            return ReceiptPrintPayload(
                printText = printText,
                receiptType = document.optString("receipt_type", "receipt"),
                paymentHash = document.optString("payment_hash", "receipt")
            )
        }
    }
}
