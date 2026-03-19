package com.example.lnbitstaptopay

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class ReceiptPrintActivity : Activity() {

    companion object {
        const val EXTRA_RECEIPT_PAYLOAD = "receipt_payload"
    }

    private var printStarted = false
    private var systemPrintLaunched = false
    private var leftForSystemPrint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val payload = intent.getStringExtra(EXTRA_RECEIPT_PAYLOAD)
        if (payload.isNullOrBlank()) {
            Log.e("TPOS_PRINT", "Missing receipt payload")
            finish()
            return
        }

        val document = runCatching { JSONObject(payload) }.getOrElse {
            Log.e("TPOS_PRINT", "Invalid receipt payload", it)
            finish()
            return
        }

        val printText = document.optString("print_text").trim()
        if (printText.isBlank()) {
            Log.e("TPOS_PRINT", "Missing print_text in receipt payload")
            finish()
            return
        }

        val receiptType = document.optString("receipt_type", "receipt")
        if (launchRawBt(printText, receiptType)) {
            finish()
            return
        }

        val webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (printStarted) return
                printStarted = true
                val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val receiptType = document.optString("receipt_type", "receipt")
                val paymentHash = document.optString("payment_hash", "receipt")
                val jobName = if (receiptType == "order_receipt") {
                    "TPoS Order $paymentHash"
                } else {
                    "TPoS Receipt $paymentHash"
                }
                systemPrintLaunched = true
                printManager.print(jobName, view.createPrintDocumentAdapter(jobName), null)
            }
        }
        webView.loadDataWithBaseURL(
            null,
            buildHtml(printText, receiptType),
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onPause() {
        super.onPause()
        if (systemPrintLaunched) {
            leftForSystemPrint = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (systemPrintLaunched && leftForSystemPrint) {
            finish()
        }
    }

    private fun launchRawBt(printText: String, receiptType: String): Boolean {
        val payload = if (receiptType == "order_receipt") {
            buildEscPosOrderReceipt(printText)
        } else {
            printText.toByteArray(StandardCharsets.UTF_8)
        }
        val encoded = Base64.encodeToString(
            payload,
            Base64.NO_WRAP
        )
        val uri = Uri.parse("rawbt:data:text/plain;base64,$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            `package` = "ru.a402d.rawbtprinter"
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val canHandle = intent.resolveActivity(packageManager) != null
        if (!canHandle) {
            Log.i("TPOS_PRINT", "RawBT not available; falling back to Android print framework")
            return false
        }
        startActivity(intent)
        Log.i("TPOS_PRINT", "Sent print job to RawBT")
        return true
    }

    private fun buildEscPosOrderReceipt(printText: String): ByteArray {
        val out = mutableListOf<Byte>()
        fun append(vararg bytes: Int) {
            bytes.forEach { out.add(it.toByte()) }
        }
        fun appendText(text: String) {
            text.toByteArray(StandardCharsets.UTF_8).forEach { out.add(it) }
        }

        append(0x1B, 0x40) // initialize
        append(0x1B, 0x61, 0x01) // center header
        append(0x1D, 0x21, 0x11) // double width + double height

        val blocks = printText.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        blocks.forEachIndexed { index, block ->
            if (index == 0) {
                appendText(block)
                appendText("\n\n")
                append(0x1B, 0x61, 0x00) // left align body
            } else {
                appendText(block)
                appendText("\n\n")
            }
        }

        append(0x1D, 0x21, 0x00) // restore normal size
        append(0x1B, 0x64, 0x03) // feed a few lines
        return out.toByteArray()
    }

    private fun buildHtml(printText: String, receiptType: String): String {
        val isOrderReceipt = receiptType == "order_receipt"
        val blocks = printText.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val content = blocks.mapIndexed { index, block ->
            val className = when (index) {
                0 -> "receipt-block receipt-block-header"
                blocks.lastIndex -> if (isOrderReceipt) {
                    "receipt-block"
                } else {
                    "receipt-block receipt-block-footer"
                }
                else -> "receipt-block"
            }
            "<div class=\"$className\">${escape(block)}</div>"
        }.joinToString("")

        return """
            <html>
            <head>
              <meta charset="utf-8" />
              <style>
                body {
                  font-family: monospace;
                  color: #111;
                  padding: 24px 18px;
                  font-size: ${if (isOrderReceipt) 27 else 18}px;
                  line-height: 1.35;
                }
                .receipt-block {
                  white-space: pre-wrap;
                  text-align: left;
                  margin-bottom: 1.1em;
                }
                .receipt-block-header,
                .receipt-block-footer {
                  text-align: center;
                }
              </style>
            </head>
            <body>$content</body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String): String = TextUtils.htmlEncode(value)
}
