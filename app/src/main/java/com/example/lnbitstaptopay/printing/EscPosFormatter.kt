package com.example.lnbitstaptopay.printing

import java.nio.charset.StandardCharsets

object EscPosFormatter {
    fun buildTestPrint(appLabel: String): ByteArray {
        val out = mutableListOf<Byte>()

        fun append(vararg bytes: Int) {
            bytes.forEach { out.add(it.toByte()) }
        }

        fun appendText(text: String) {
            text.toByteArray(StandardCharsets.UTF_8).forEach { out.add(it) }
        }

        append(0x1B, 0x40)
        append(0x1B, 0x61, 0x01)
        append(0x1D, 0x21, 0x11)
        appendText(appLabel)
        appendText("\n")
        append(0x1D, 0x21, 0x00)
        appendText("Bluetooth printer test\n\n")
        append(0x1B, 0x61, 0x00)
        appendText("If you can read this,\n")
        appendText("native printing is working.\n\n")
        appendText("Receipt path can now be wired\n")
        appendText("to this transport.\n\n")
        append(0x1D, 0x56, 0x00)
        append(0x1B, 0x64, 0x03)
        return out.toByteArray()
    }
}
