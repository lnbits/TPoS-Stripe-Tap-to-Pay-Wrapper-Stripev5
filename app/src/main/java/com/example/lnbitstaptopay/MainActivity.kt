package com.example.lnbitstaptopay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.androidbrowserhelper.trusted.TwaLauncher

// Stripe Terminal SDK (5.1.1)
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
import com.stripe.stripeterminal.log.LogLevel

// OkHttp
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Callback as OkHttpCallback

// JSON
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

// QR (ZXing modern API)
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// Eligibility + dialogs
import android.app.AlertDialog
import android.nfc.NfcAdapter
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // ---- persisted config defaults
    private val BACKEND_ORIGIN_DEFAULT = "" // no scheme
    private val TPOS_ID_DEFAULT = ""
    private val ADMIN_BEARER_TOKEN_DEFAULT = ""
    private val TERMINAL_LOCATION_ID_DEFAULT = ""

    private val prefs by lazy { getSharedPreferences("tpos_prefs", MODE_PRIVATE) }
    private fun cfgOrigin() = prefs.getString("origin", BACKEND_ORIGIN_DEFAULT)!!
    private fun cfgTposId() = prefs.getString("tposId", TPOS_ID_DEFAULT)!!
    private fun cfgBearer() = prefs.getString("bearer", ADMIN_BEARER_TOKEN_DEFAULT)!!
    private fun cfgLocId()  = prefs.getString("locId", TERMINAL_LOCATION_ID_DEFAULT)!!
    private fun cfgWrapperMode() = prefs.getBoolean("wrapperMode", false)
    private fun hasStripeLocationConfig(): Boolean = cfgLocId().isNotBlank()
    private fun hasStripeAuthConfig(): Boolean = cfgBearer().isNotBlank()
    private fun cfgSimulated() = prefs.getBoolean("simulated", BuildConfig.DEBUG)
    private fun cfgKioskMode() = prefs.getBoolean("kioskMode", false)
    private fun cfgSavedScreenTimeout() = prefs.getInt("savedScreenTimeoutMs", -1)
    private fun hasSavedConfig(): Boolean =
        prefs.contains("origin") && prefs.contains("tposId")

    private fun tposUrl(): String {
        val base = "https://${cfgOrigin()}/tpos/${cfgTposId()}"
        return if (cfgWrapperMode()) "$base?wrapper=true" else base
    }
    private fun stripeBase() = "https://${cfgOrigin()}/api/v1/fiat/stripe"

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Volatile private var busy = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var twaLauncher: TwaLauncher? = null
    private var terminalInitialized = false
    private var pairingHandledForCurrentIntent = false

    private var btnContinue: Button? = null
    private var tvSummary: TextView? = null
    private var btnSim: Button? = null
    private var btnKiosk: Button? = null

    // Discovery/connect state
    private var discoveryStarted = false
    private var readerConnected = false
    private var discoveryTimeoutRunnable: Runnable? = null

    // ---- permission launcher (register once)
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var onPermsGranted: (() -> Unit)? = null
    private var onPermsDenied: ((String) -> Unit)? = null

    // ---- QR Scanner launcher (modern API)
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val ok = saveFromPairingUrl(result.contents!!)
            if (ok) {
                Log.i("TPOS_PAIR", "Saved config from pairing URL")
                refreshOnboardingUi()
            } else {
                Log.e("TPOS_PAIR", "Invalid pairing URL: ${result.contents}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Register runtime permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val allGranted = grants.values.all { it }
            if (allGranted) onPermsGranted?.invoke()
            else onPermsDenied?.invoke("Required permissions not granted")
            onPermsGranted = null
            onPermsDenied = null
        }

        initOnboardingUi()
        if (cfgKioskMode()) {
            applyKioskScreenTimeoutOrPrompt()
            syncKioskModeToService()
        }

        // Handle incoming deep-link pairing payloads on first launch.
        handleIncomingIntent(intent, autoStartFlow = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingHandledForCurrentIntent = false
        // Handle deep-links delivered to existing singleTop activity instances.
        handleIncomingIntent(intent, autoStartFlow = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        twaLauncher?.destroy()
        twaLauncher = null
    }

    private fun initOnboardingUi() {
        val btnScan = findViewById<Button>(R.id.btnScan)
        val continueBtn = findViewById<Button>(R.id.btnContinue)
        val summary = findViewById<TextView>(R.id.tvSummary)
        val simBtn = findViewById<Button>(R.id.btnSimulated)
        val kioskBtn = findViewById<Button>(R.id.btnKioskMode)
        btnContinue = continueBtn
        tvSummary = summary
        btnSim = simBtn
        btnKiosk = kioskBtn
        refreshOnboardingUi()

        btnScan.setOnClickListener {
            val opts = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan Pairing Link")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
            qrLauncher.launch(opts)
        }

        continueBtn.setOnClickListener {
            startPosFlow()
        }

        simBtn.setOnClickListener {
            val next = !cfgSimulated()
            prefs.edit().putBoolean("simulated", next).apply()
            refreshOnboardingUi()
        }

        kioskBtn.setOnClickListener {
            val next = !cfgKioskMode()
            prefs.edit().putBoolean("kioskMode", next).apply()
            if (next) applyKioskScreenTimeoutOrPrompt() else restoreScreenTimeoutIfNeeded()
            refreshOnboardingUi()
            syncKioskModeToService()
        }
    }

    private fun refreshOnboardingUi() {
        val continueBtn = btnContinue ?: return
        val summary = tvSummary ?: return
        val simBtn = btnSim ?: return
        val kioskBtn = btnKiosk ?: return
        if (hasSavedConfig()) {
            continueBtn.visibility = View.VISIBLE
            summary.text = if (hasStripeLocationConfig()) {
                "${tposUrl()} (pos=${cfgLocId()})"
            } else {
                "${
                    tposUrl()
                } (without Stripe)"
            }
        } else {
            continueBtn.visibility = View.GONE
            summary.text = ""
        }
        simBtn.text = if (cfgSimulated()) "Simulated: ON" else "Simulated: OFF"
        kioskBtn.text = if (cfgKioskMode()) "Kiosk mode: ON" else "Kiosk mode: OFF"
    }

    private fun syncKioskModeToService() {
        val intent = Intent(this, TposWebSocketService::class.java)
            .setAction(TposWebSocketService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun applyKioskScreenTimeoutOrPrompt() {
        if (!Settings.System.canWrite(this)) {
            AlertDialog.Builder(this)
                .setTitle("Allow modify system settings")
                .setMessage(
                    "To keep the screen from sleeping in Kiosk mode, allow this app to modify system settings, then return here."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    val uri = Uri.parse("package:$packageName")
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, uri))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        val resolver = contentResolver
        val current = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrDefault(60_000)

        if (cfgSavedScreenTimeout() <= 0) {
            prefs.edit().putInt("savedScreenTimeoutMs", max(current, 1_000)).apply()
        }

        // Use Android's max int timeout to approximate "never sleep" where OEM policy allows it.
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE)
        Log.i("TPOS_KIOSK", "Applied kiosk screen timeout override (max value)")
    }

    private fun restoreScreenTimeoutIfNeeded() {
        if (!Settings.System.canWrite(this)) return
        val saved = cfgSavedScreenTimeout()
        if (saved <= 0) return
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved)
        prefs.edit().remove("savedScreenTimeoutMs").apply()
        Log.i("TPOS_KIOSK", "Restored prior screen timeout: ${saved}ms")
    }

    private fun handleIncomingIntent(incoming: Intent?, autoStartFlow: Boolean) {
        if (incoming == null || pairingHandledForCurrentIntent) return
        if (incoming.action != Intent.ACTION_VIEW) return
        val payload = incoming.dataString ?: return
        pairingHandledForCurrentIntent = true
        val ok = saveFromPairingUrl(payload)
        if (!ok) {
            Log.e("TPOS_PAIR", "Deep link payload was not a valid pairing URL: $payload")
            return
        }

        Log.i("TPOS_PAIR", "Saved config from deep link payload")
        refreshOnboardingUi()
        if (autoStartFlow) {
            mainHandler.post { startPosFlow() }
        }
    }

    private fun saveFromPairingUrl(url: String): Boolean {
        return runCatching {
            val u = url.toUri()
            val host = u.host ?: return false
            val port = if (u.port != -1) ":${u.port}" else ""
            val segs = u.pathSegments
            if (segs.size < 2 || segs[0] != "tpos") return false
            val tposId = segs[1]
            val auth = u.getQueryParameter("auth")
            val pos  = u.getQueryParameter("pos")
            val wrapperMode = u.getQueryParameter("wrapper") == "true"

            prefs.edit()
                .putString("origin", host + port)
                .putString("tposId", tposId)
                .putBoolean("wrapperMode", wrapperMode)
                .apply {
                    if (auth.isNullOrBlank()) remove("bearer")
                    else putString("bearer", auth)
                    if (pos.isNullOrBlank()) remove("locId")
                    else putString("locId", pos)
                }
                .apply()
            true
        }.getOrDefault(false)
    }

    // ---------- Tap to Pay eligibility helpers ----------
    private fun hasPlayServices(): Pair<Boolean, String?> {
        val gaa = GoogleApiAvailability.getInstance()
        val code = gaa.isGooglePlayServicesAvailable(this)
        val ok = (code == ConnectionResult.SUCCESS)
        val msg = if (ok) null else gaa.getErrorString(code)
        return ok to msg
    }

    private fun maybeResolvePlayServices(): Boolean {
        val gaa = GoogleApiAvailability.getInstance()
        val code = gaa.isGooglePlayServicesAvailable(this)
        if (gaa.isUserResolvableError(code)) {
            gaa.getErrorDialog(this, code, 0)?.show()
            return true
        }
        return false
    }

    private fun hasPlayStore(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo("com.android.vending", PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo("com.android.vending", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasNfcEnabled(): Boolean =
        NfcAdapter.getDefaultAdapter(this)?.isEnabled == true

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun supportsTapToPayDiscovery(): Boolean = try {
        true // keep true unless you wire in Terminal.supportsDiscoveryMethod(...)
    } catch (_: Throwable) {
        true
    }

    private fun tapToPayEligibleReason(): String? {
        if (cfgSimulated()) return null

        val (hasGms, gmsReason) = hasPlayServices()
        if (!hasGms) {
            return "Google Play services unavailable${gmsReason?.let { ": $it" } ?: ""}"
        }
        if (!hasPlayStore()) {
            return "Google Play Store is not installed on this device"
        }
        if (!hasNfcEnabled()) {
            return "NFC is disabled"
        }
        if (!isLocationEnabled()) {
            return "Location services are disabled"
        }
        if (!supportsTapToPayDiscovery()) {
            return "Tap to Pay discovery is not supported by this Stripe SDK/device combination"
        }
        return null
    }

    private fun showUnsupportedDialog(reason: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle("Device not supported")
            .setMessage(
                "Stripe Tap to Pay can't run on this device.\n\n" +
                        "Reason: $reason\n\n" +
                        "Use a supported phone (e.g., Samsung/Pixel) or pair an external Stripe reader."
            )
            .setPositiveButton("OK", null)

        when {
            reason.contains("NFC", ignoreCase = true) -> {
                builder.setNegativeButton("Open NFC settings") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (_: Exception) {}
                }
            }
            reason.contains("Location", ignoreCase = true) -> {
                builder.setNegativeButton("Enable location") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
        builder.show()
    }
    // ----------------------------------------------------

    private fun startPosFlow() {
        if (!hasStripeLocationConfig()) {
            showRegistrationScreen()
            return
        }

        if (!hasStripeAuthConfig()) {
            Log.e("TPOS_TTP", "Stripe location is configured, but auth token is missing")
            AlertDialog.Builder(this)
                .setTitle("Stripe setup incomplete")
                .setMessage("This pairing includes a Stripe POS location but no auth token. Scan a full Stripe pairing code or remove the pos parameter to use the wrapper without Stripe.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val reason = tapToPayEligibleReason()
        if (reason != null) {
            Log.e("TPOS_TTP", "Tap to Pay not available on this device: $reason")
            if (reason.startsWith("Google Play services") && maybeResolvePlayServices()) {
                return
            }
            showUnsupportedDialog(reason)
            return
        }

        if (!terminalInitialized) {
            Terminal.init(
                applicationContext,
                LogLevel.VERBOSE,
                tokenProvider(),
                object : TerminalListener {},
                null
            )
            terminalInitialized = true
        }

        // Show the registration modal ONLY when user taps Continue
        showRegistrationScreen()
    }

    private fun cancelDiscoveryTimeout() {
        discoveryTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryTimeoutRunnable = null
    }

    private fun postDiscoveryTimeout(onSlow: () -> Unit) {
        cancelDiscoveryTimeout()
        val timeout = Runnable {
            discoveryTimeoutRunnable = null
            if (!readerConnected) {
                Log.w("TPOS_TTP", "Tap to Pay registration is still in progress after timeout window")
                onSlow()
            }
        }
        discoveryTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 30_000L)
    }

    data class TokenResp(val secret: String?)

    private fun startWsService() {
        val intent = Intent(this, TposWebSocketService::class.java)
            .setAction(TposWebSocketService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    // ---- request perms helper (kept for re-use inside registration)
    private fun hasAllRuntimePerms(): Boolean {
        val want = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            want += Manifest.permission.POST_NOTIFICATIONS
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            want += Manifest.permission.BLUETOOTH_SCAN
            want += Manifest.permission.BLUETOOTH_CONNECT
        }
        return want.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun ensurePermissions(onGranted: () -> Unit, onDenied: (String) -> Unit) {
        val need = mutableListOf<String>()
        val api = android.os.Build.VERSION.SDK_INT

        if (api >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (api >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_SCAN
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.ACCESS_FINE_LOCATION

        if (need.isEmpty()) { onGranted(); return }

        onPermsGranted = onGranted
        onPermsDenied  = onDenied
        permissionLauncher.launch(need.toTypedArray())
    }

    private fun Request.Builder.withBearer(): Request.Builder =
        this.header("Authorization", "Bearer ${cfgBearer()}")

    private fun tokenProvider() = object : ConnectionTokenProvider {
        override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
            val req = Request.Builder()
                .url("${stripeBase()}/connection_token")
                .header("accept", "application/json")
                .withBearer()
                .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            http.newCall(req).enqueue(object : OkHttpCallback {
                override fun onFailure(call: Call, e: IOException) {
                    callback.onFailure(ConnectionTokenException(e.message ?: "Failed to fetch connection token", e))
                }
                override fun onResponse(call: Call, resp: Response) {
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        callback.onFailure(ConnectionTokenException("HTTP ${resp.code}: $body"))
                        return
                    }
                    val secret = moshi.adapter(TokenResp::class.java).fromJson(body)?.secret
                    if (secret.isNullOrBlank()) callback.onFailure(ConnectionTokenException("No secret"))
                    else callback.onSuccess(secret)
                }
            })
        }
    }

    // ---- DISCOVER + CONNECT with progress + watchdog (for registration) ----
    @SuppressLint("MissingPermission")
    private fun discoverAndConnect(
        onReady: () -> Unit,
        onError: (String) -> Unit,
        onUpdate: ((Int) -> Unit)? = null,
        onDebug: ((String) -> Unit)? = null
    ) {
        if (!hasAllRuntimePerms()) {
            onError("Missing runtime permissions (Location/Bluetooth).")
            return
        }

        try {
            Terminal.getInstance().connectedReader?.let {
                readerConnected = true
                onReady()
                return
            }
        } catch (_: Throwable) {
            // Ignore and fall through to discovery.
        }

        val discoveryConfig = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = cfgSimulated()
        )
        var finished = false
        fun finishReady() {
            if (finished) return
            finished = true
            onReady()
        }
        fun finishError(message: String) {
            if (finished) return
            finished = true
            onError(message)
        }

        fun startAttempt(attempt: Int) {
            if (finished) return
            var connectAttempted = false
            var discoveryCallbackSeen = false
            onDebug?.invoke("Starting discovery attempt $attempt")

            val noCallbackWatchdog = Runnable {
                if (finished || discoveryCallbackSeen || readerConnected) return@Runnable
                val cr = try { Terminal.getInstance().connectedReader } catch (_: Throwable) { null }
                if (cr != null) {
                    readerConnected = true
                    onDebug?.invoke("Watchdog saw connectedReader during discovery attempt $attempt")
                    finishReady()
                    return@Runnable
                }

                onDebug?.invoke("No discovery callback from Stripe on attempt $attempt")
                if (attempt < 2) {
                    onDebug?.invoke("Retrying discovery once")
                    startAttempt(attempt + 1)
                } else {
                    finishError("Stripe discovery did not respond. Please try again.")
                }
            }

            try {
                Terminal.getInstance().discoverReaders(
                    discoveryConfig,
                    object : DiscoveryListener {
                        override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                            try {
                                discoveryCallbackSeen = true
                                discoveryStarted = true
                                onDebug?.invoke("Discovery callback delivered ${readers.size} reader(s) on attempt $attempt")
                                onDebug?.invoke("Terminal connectionStatus=${Terminal.getInstance().connectionStatus}")
                                onUpdate?.invoke(readers.size)
                                Log.i("TPOS_WS", "Discovered readers: ${readers.size}")

                                Terminal.getInstance().connectedReader?.let {
                                    readerConnected = true
                                    onDebug?.invoke("connectedReader already available during discovery callback")
                                    finishReady()
                                    return
                                }
                                if (readers.isEmpty() || connectAttempted) return

                                startConnectedReaderWatchdog(::finishReady)
                                connectAttempted = true
                                val firstReader = readers.first()
                                onDebug?.invoke(
                                    "Preparing connectReader for deviceType=${firstReader.deviceType} serial=${firstReader.serialNumber ?: "unknown"} using location=${cfgLocId()}"
                                )

                                val cfg = ConnectionConfiguration.TapToPayConnectionConfiguration(
                                    locationId = cfgLocId(),
                                    autoReconnectOnUnexpectedDisconnect = true,
                                    tapToPayReaderListener = null
                                )
                                onDebug?.invoke("Calling connectReader now")
                                Terminal.getInstance().connectReader(
                                    firstReader,
                                    cfg,
                                    object : ReaderCallback {
                                        override fun onSuccess(reader: Reader) {
                                            readerConnected = true
                                            onDebug?.invoke("connectReader succeeded on attempt $attempt")
                                            finishReady()
                                        }
                                        override fun onFailure(e: TerminalException) {
                                            val msg = "Connect failed [${e.errorCode}]: ${e.errorMessage}"
                                            Log.e("TPOS_WS", msg, e)
                                            onDebug?.invoke(msg)
                                            Terminal.getInstance().connectedReader?.let { finishReady(); return }
                                            finishError(msg)
                                        }
                                    }
                                )
                            } catch (t: Throwable) {
                                val msg = "Exception in discovery callback: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                                Log.e("TPOS_WS", msg, t)
                                onDebug?.invoke(msg)
                                finishError(msg)
                            }
                        }
                    },
                    object : Callback {
                        override fun onSuccess() {
                            discoveryStarted = true
                            onDebug?.invoke("discoverReaders started successfully on attempt $attempt")
                        }
                        override fun onFailure(e: TerminalException) {
                            val msg = "Discovery failed [${e.errorCode}]: ${e.errorMessage}"
                            Log.e("TPOS_WS", msg, e)
                            onDebug?.invoke(msg)
                            finishError(msg)
                        }
                    }
                )
                mainHandler.postDelayed(noCallbackWatchdog, 15_000L)
            } catch (se: SecurityException) {
                Log.e("TPOS_PERM", "discoverReaders SecurityException", se)
                val msg = "Permission error starting discovery: ${se.message ?: "SecurityException"}"
                onDebug?.invoke(msg)
                finishError(msg)
            }
        }

        startAttempt(1)
    }

    private fun startConnectedReaderWatchdog(onReady: () -> Unit) {
        val start = System.currentTimeMillis()
        fun tick() {
            val cr = try { Terminal.getInstance().connectedReader } catch (_: Throwable) { null }
            if (cr != null) {
                readerConnected = true
                onReady()
                return
            }
            if (System.currentTimeMillis() - start > 12_000L) return
            mainHandler.postDelayed({ tick() }, 500L)
        }
        mainHandler.postDelayed({ tick() }, 500L)
    }

    // ---- REGISTRATION MODAL (pre-page) ----
    private fun showRegistrationScreen() {
        val view = layoutInflater.inflate(R.layout.dialog_registration_status, null)
        val tvTitle   = view.findViewById<TextView>(R.id.tvRegTitle)
        val tvStep    = view.findViewById<TextView>(R.id.tvRegStep)
        val tvDetail  = view.findViewById<TextView>(R.id.tvRegDetail)
        val tvDebug   = view.findViewById<TextView>(R.id.tvRegDebug)
        val btnClose  = view.findViewById<Button>(R.id.btnRegClose)
        val btnGo     = view.findViewById<Button>(R.id.btnRegContinue)
        var registrationSettled = false
        var dialogActive = true
        var registrationObserver: Runnable? = null
        val debugLines = ArrayDeque<String>()

        tvTitle.text = "Registering this device with Stripe"
        tvStep.text  = "Starting…"
        tvDetail.text = "Checking device eligibility and connecting…"
        tvDebug.text = ""
        btnGo.isEnabled = false
        btnGo.text = "Continue"

        val dlg = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        fun onUi(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block()
            else mainHandler.post { if (dialogActive) block() }
        }

        fun debugStatus(message: String) {
            Log.i("TPOS_REG", message)
            onUi {
                if (!dialogActive) return@onUi
                if (debugLines.size >= 8) debugLines.removeFirst()
                debugLines.addLast(message)
                tvDebug.text = debugLines.joinToString("\n")
            }
        }

        fun onPhase(label: String, detail: String = "") {
            onUi {
                if (!dialogActive || registrationSettled) return@onUi
                tvStep.text = label
                tvDetail.text = detail
            }
            debugStatus("UI phase: $label${if (detail.isBlank()) "" else " | $detail"}")
        }
        fun onRegistered(reader: Reader) {
            val rn = reader.serialNumber ?: "unknown"
            val dt = reader.deviceType?.name ?: "TapToPay"
            val lid = cfgLocId()
            onUi {
                if (!dialogActive || registrationSettled) return@onUi
                registrationSettled = true
                cancelDiscoveryTimeout()
                registrationObserver?.let { mainHandler.removeCallbacks(it) }
                registrationObserver = null
                tvTitle.text = "Registered ✅"
                tvStep.text  = "Connected as software reader"
                tvDetail.text = "Reader: $dt • SN: $rn\nLocation: $lid"
                btnGo.isEnabled = true
            }
            debugStatus("Reader connected: type=$dt serial=$rn location=$lid")
        }
        fun onFail(msg: String) {
            onUi {
                if (!dialogActive || registrationSettled) return@onUi
                registrationSettled = true
                cancelDiscoveryTimeout()
                registrationObserver?.let { mainHandler.removeCallbacks(it) }
                registrationObserver = null
                tvTitle.text = "Couldn’t register"
                tvStep.text  = "Error"
                tvDetail.text = msg
                btnGo.isEnabled = false
            }
            debugStatus("Registration failed: $msg")
        }

        fun startRegistrationObserver() {
            registrationObserver?.let { mainHandler.removeCallbacks(it) }
            val watcher = object : Runnable {
                override fun run() {
                    if (!dialogActive || registrationSettled) return
                    val cr = try { Terminal.getInstance().connectedReader } catch (_: Throwable) { null }
                    if (cr != null) {
                        readerConnected = true
                        debugStatus("Observer saw connectedReader become available")
                        onRegistered(cr)
                        return
                    }
                    mainHandler.postDelayed(this, 500L)
                }
            }
            registrationObserver = watcher
            debugStatus("Started connectedReader observer")
            mainHandler.postDelayed(watcher, 500L)
        }

        btnClose.setOnClickListener {
            dialogActive = false
            cancelDiscoveryTimeout()
            registrationObserver?.let { mainHandler.removeCallbacks(it) }
            registrationObserver = null
            dlg.dismiss()
        }
        btnGo.setOnClickListener {
            dialogActive = false
            cancelDiscoveryTimeout()
            registrationObserver?.let { mainHandler.removeCallbacks(it) }
            registrationObserver = null
            dlg.dismiss()
            launchPosShell()
        }
        dlg.setOnDismissListener {
            dialogActive = false
            cancelDiscoveryTimeout()
            registrationObserver?.let { mainHandler.removeCallbacks(it) }
            registrationObserver = null
        }

        dlg.show()

        if (!hasSavedConfig()) {
            tvTitle.text = "Pair device to continue"
            tvStep.text = "Waiting for pairing"
            tvDetail.text = "Scan the pairing link QR code to set Origin, TPoS ID, Token, and Location."
            debugStatus("No saved pairing config present")
            btnGo.isEnabled = false
            return
        }

        if (!hasStripeLocationConfig()) {
            tvTitle.text = "Using without Stripe"
            tvStep.text = "Continue"
            tvDetail.text = "This pairing does not include a Stripe POS location. Continue to open the TPoS wrapper directly."
            debugStatus("Saved config found without Stripe location; skipping Stripe registration")
            btnGo.isEnabled = true
            return
        }

        debugStatus("Saved config found for location=${cfgLocId()} simulated=${cfgSimulated()}")
        val reason = tapToPayEligibleReason()
        if (reason != null) { onFail("Device not eligible: $reason"); return }
        debugStatus("Eligibility checks passed")

        if (!terminalInitialized) {
            onPhase("Initializing Stripe SDK")
            Terminal.init(
                applicationContext,
                LogLevel.VERBOSE,
                tokenProvider(),
                object : TerminalListener {},
                null
            )
            terminalInitialized = true
            debugStatus("Terminal.init completed")
        } else {
            debugStatus("Terminal already initialized")
        }

        discoveryStarted = false
        readerConnected = false
        startRegistrationObserver()
        postDiscoveryTimeout {
            onPhase(
                "Still connecting…",
                "Initial Tap to Pay registration can take longer on some devices. Leave this screen open while Stripe finishes setup."
            )
        }

        ensurePermissions(
            onGranted = {
                debugStatus("Runtime permissions granted")
                onPhase("Discovering Tap to Pay reader", "simulated=${cfgSimulated()}")
                discoverAndConnect(
                    onReady = {
                        val cr = Terminal.getInstance().connectedReader
                        if (cr != null) onRegistered(cr) else onFail("Connected, but reader not available")
                    },
                    onError = { e ->
                        debugStatus("discoverAndConnect error: $e")
                        onFail(e)
                    },
                    onUpdate = { count ->
                        debugStatus("Discovery callback: found $count candidate reader(s)")
                        onPhase("Discovering…", "Found $count candidate${if (count==1) "" else "s"} — connecting…")
                    },
                    onDebug = ::debugStatus
                )
            },
            onDenied = { e ->
                debugStatus("Runtime permissions denied: $e")
                onFail("Permissions denied: $e")
            }
        )
    }

    private fun launchPosShell() {
        startWsService()
        if (twaLauncher == null) twaLauncher = TwaLauncher(this)
        twaLauncher?.launch(Uri.parse(tposUrl()))
    }
}
