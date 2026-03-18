package com.example.lnbitstaptopay

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.text.TextUtils
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class ReceiptPrintActivity : Activity() {

    companion object {
        const val EXTRA_RECEIPT_PAYLOAD = "receipt_payload"
    }

    private var printStarted = false

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
                printManager.print(jobName, view.createPrintDocumentAdapter(jobName), null)
                view.postDelayed({ finish() }, 750L)
            }
        }
        val receiptType = document.optString("receipt_type", "receipt")
        webView.loadDataWithBaseURL(
            null,
            buildHtml(printText, receiptType),
            "text/html",
            "UTF-8",
            null
        )
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
                  font-size: 18px;
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
