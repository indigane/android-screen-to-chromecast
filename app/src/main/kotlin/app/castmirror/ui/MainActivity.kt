package app.castmirror.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.castmirror.R
import app.castmirror.core.discovery.CastDevice
import app.castmirror.discovery.DeviceDiscovery
import app.castmirror.probe.ProbeRunner
import app.castmirror.service.MirrorService
import app.castmirror.service.MirrorStatus
import app.castmirror.settings.AppIdMode
import app.castmirror.settings.CodecChoice
import app.castmirror.settings.MirrorSettings
import app.castmirror.settings.ResolutionPreset
import app.castmirror.util.AppLog

/**
 * The whole user interface: pick a receiver, adjust the encode settings, start or stop mirroring,
 * and read the log.
 *
 * The layout is built in code rather than inflated from XML. The app deliberately depends on
 * nothing but the Android framework and the Kotlin standard library — no AndroidX, no Material —
 * so there is no `ConstraintLayout` and no view binding to gain from XML, and a single file that
 * builds the tree is easier to follow than a layout plus the code that wires it up.
 *
 * The log pane is the most important element on the screen. Mirroring fails for reasons that are
 * invisible from the outside — a receiver that does not know the app id, an OFFER in the wrong
 * dialect, an mDNS record with no `ca` bit — and the log is the only place those become
 * diagnosable. It is fed from [AppLog], which every layer writes to.
 */
class MainActivity : Activity() {

    private lateinit var settings: MirrorSettings
    private lateinit var discovery: DeviceDiscovery

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var deviceGroup: RadioGroup
    private lateinit var emptyDevicesView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var probeButton: Button
    private lateinit var bitrateField: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var devices: List<CastDevice> = emptyList()

    /**
     * The selected receiver, as its stable `host:port` key — the single source of truth for
     * "which device", never a list position or a framework-restored radio-button id.
     *
     * It is persisted (see [persistSelection]) so it survives the Activity being recreated or the
     * process being evicted while a background session runs, and is reconciled against the running
     * [MirrorService]'s target so the UI can never show a device other than the one being streamed
     * to. Setting it always goes through [applySelection].
     */
    private var selectedDeviceKey: String? = null

    private val selectionPrefs by lazy { getSharedPreferences("castmirror", Context.MODE_PRIVATE) }

    /** Stable View id per device key, so refresh updates rows in place instead of recreating them. */
    private val deviceRowIds = HashMap<String, Int>()

    /** Last-seen record per key, so a selected device that drops from one pass stays in the list. */
    private val lastKnownByKey = LinkedHashMap<String, CastDevice>()

    /**
     * Set while [renderDevices] is structurally changing the group.
     *
     * `RadioGroup.removeView`/`clearCheck` fire the checked-change listener with `-1` mid-rebuild;
     * without this guard that transient would null out the user's selection on every discovery
     * pass — the observed "selection disappears, then taps deselect" bug.
     */
    private var suppressCheckListener = false
    private var boundService: MirrorService? = null
    private var startAfterPermission = false
    private var askedRecordAudio = false
    private var probeInFlight = false

    private val logListener = AppLog.Listener { runOnUiThread { renderLog() } }

    private val discoveryListener = DeviceDiscovery.Listener { list ->
        devices = list
        renderDevices()
    }

    private val statusListener = MirrorService.StateListener { status -> renderStatus(status) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val local = (service as? MirrorService.LocalBinder)?.service ?: return
            boundService = local
            // Reconcile the shown selection with the exact device the service is streaming to
            // before subscribing, so a running session's target always wins over any restored or
            // persisted guess.
            reconcileWithService(local.currentStatus())
            local.addListener(statusListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.removeListener(statusListener)
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MirrorSettings(this)
        discovery = DeviceDiscovery(this)
        // Restore the selection by id before the first render: a config change (Bundle) takes
        // precedence, otherwise the persisted last-used key. A running session is reconciled on top
        // of this once the service binds.
        selectedDeviceKey = savedInstanceState?.getString(STATE_SELECTED_DEVICE)
            ?: MirrorService.lastTargetKey.takeIf { MirrorService.isRunning }
            ?: selectionPrefs.getString(KEY_SELECTED_DEVICE, null)
        setContentView(buildLayout())
        renderStatus(MirrorStatus(MirrorService.isRunning, null, null, getString(R.string.status_idle)))
        renderLog()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the selection by key, not by radio-button position/id, so a recreated Activity
        // re-checks the same device rather than whatever row inherits a colliding view id.
        outState.putString(STATE_SELECTED_DEVICE, selectedDeviceKey)
    }

    override fun onStart() {
        super.onStart()
        AppLog.addListener(logListener)
        discovery.addListener(discoveryListener)
        discovery.start()
        bindService(Intent(this, MirrorService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        persistBitrate()
        AppLog.removeListener(logListener)
        discovery.removeListener(discoveryListener)
        // Discovery is stopped with the UI: it holds a multicast lock, and leaving that held while
        // the app is in the background is a battery cost with no benefit.
        discovery.stop()
        boundService?.removeListener(statusListener)
        runCatching { unbindService(connection) }
        boundService = null
        super.onStop()
    }

    // ---- layout -------------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        scroll.addView(content)
        root.addView(scroll)

        content.addView(heading(getString(R.string.app_name)))
        statusView = body(getString(R.string.status_idle)).apply { setPadding(0, dp(4), 0, 0) }
        content.addView(statusView)
        statsView = caption("").apply { visibility = View.GONE }
        content.addView(statsView)

        content.addView(sectionTitle(getString(R.string.section_receivers)))
        content.addView(
            buttonRow(
                button(getString(R.string.action_refresh)) { onRefresh() },
                button(getString(R.string.action_enter_ip)) { onEnterAddress() },
            )
        )
        emptyDevicesView = caption(getString(R.string.devices_searching))
        content.addView(emptyDevicesView)
        deviceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            // The framework saves/restores checked state by view id. Row ids are generated fresh on
            // every recreation, so a restore can re-check a *different* device whose id happens to
            // collide with the saved one. Selection is owned here, keyed by device, so the
            // framework's view-state restore is disabled to keep it from fighting that.
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checkedId ->
                if (suppressCheckListener || checkedId == View.NO_ID) return@setOnCheckedChangeListener
                val key = deviceRowIds.entries.firstOrNull { it.value == checkedId }?.key
                // A user tap is a deliberate pick: record it as the persisted source of truth.
                if (key != null) applySelection(key, persist = true, reRender = false)
            }
        }
        content.addView(deviceGroup)

        startButton = button(getString(R.string.action_start)) { beginSession() }
        stopButton = button(getString(R.string.action_stop)) { onStopMirroring() }
        probeButton = button(getString(R.string.action_probe)) { onProbe() }
        content.addView(buttonRow(startButton, stopButton, probeButton))

        content.addView(sectionTitle(getString(R.string.section_settings)))
        content.addView(
            spinnerRow(
                getString(R.string.setting_resolution),
                ResolutionPreset.entries.map { it.label },
                ResolutionPreset.entries.indexOf(settings.resolution),
            ) { settings.resolution = ResolutionPreset.entries[it] }
        )
        content.addView(
            spinnerRow(
                getString(R.string.setting_frame_rate),
                MirrorSettings.FRAME_RATE_CHOICES.map { "$it fps" },
                MirrorSettings.FRAME_RATE_CHOICES.indexOf(settings.frameRate).coerceAtLeast(0),
            ) { settings.frameRate = MirrorSettings.FRAME_RATE_CHOICES[it] }
        )
        bitrateField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.bitrateKbps.toString())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            // Persist on focus loss so a typed value round-trips even without starting a session.
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistBitrate() }
        }
        content.addView(labelled(getString(R.string.setting_bitrate), bitrateField))
        content.addView(
            spinnerRow(
                getString(R.string.setting_codec),
                CodecChoice.entries.map { it.label },
                CodecChoice.entries.indexOf(settings.codec),
            ) { settings.codec = CodecChoice.entries[it] }
        )
        content.addView(
            spinnerRow(
                getString(R.string.setting_app_id),
                AppIdMode.entries.map { it.label },
                AppIdMode.entries.indexOf(settings.appIdMode),
            ) { settings.appIdMode = AppIdMode.entries[it] }
        )
        content.addView(
            CheckBox(this).apply {
                text = getString(R.string.setting_audio)
                isChecked = settings.mirrorAudio
                setOnCheckedChangeListener { _, checked -> settings.mirrorAudio = checked }
            }
        )
        content.addView(
            caption(getString(R.string.setting_audio_explanation)).apply {
                // Hidden device-test control: long-press toggles ADTS vs raw-AU AAC framing.
                setOnLongClickListener {
                    settings.aacAdts = !settings.aacAdts
                    toast(getString(R.string.toast_aac_adts, if (settings.aacAdts) "ON" else "OFF (raw AU)"))
                    true
                }
            }
        )
        content.addView(
            CheckBox(this).apply {
                text = getString(R.string.setting_verbose)
                isChecked = settings.verboseDiagnostics
                setOnCheckedChangeListener { _, checked -> settings.verboseDiagnostics = checked }
            }
        )
        content.addView(caption(getString(R.string.setting_verbose_explanation)))

        root.addView(buildLogPane())
        return root
    }

    private fun buildLogPane(): View {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(220))
            setPadding(dp(16), 0, dp(16), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            sectionTitle(getString(R.string.section_log)).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
        )
        header.addView(
            button(getString(R.string.action_clear_log)) {
                AppLog.clear()
                renderLog()
            }
        )
        pane.addView(header)

        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        logView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        logScroll.addView(logView)
        pane.addView(logScroll)
        return pane
    }

    // ---- actions ------------------------------------------------------------------------------

    private fun onRefresh() {
        AppLog.i("ui: refreshing discovery")
        // The selection is kept: the selected device stays in the list (via lastKnownByKey) while
        // discovery repopulates, so a refresh does not make the user re-pick.
        devices = emptyList()
        renderDevices()
        discovery.refresh()
    }

    /** Prompts for a receiver address, for networks where mDNS does not cross to the phone. */
    private fun onEnterAddress() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.dialog_address_hint)
            setText(settings.lastManualHost)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_address_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entry = field.text.toString().trim()
                if (entry.isEmpty()) return@setPositiveButton
                val (host, port) = splitHostPort(entry)
                settings.lastManualHost = entry
                discovery.addManual(host, port)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Begins a session: notification permission first, then screen-capture consent. */
    private fun beginSession() {
        val device = selectedDevice() ?: run {
            toast(getString(R.string.error_no_device))
            return
        }
        persistBitrate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // A foreground service without a visible notification is a service the user cannot
            // stop, so the permission is requested before anything starts rather than after.
            startAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }

        // Ask for RECORD_AUDIO alongside the capture consent when internal audio is on. If the user
        // declines, the session still starts and mirrors video only. Asked at most once per attempt.
        if (settings.mirrorAudio && !askedRecordAudio &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            askedRecordAudio = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        askedRecordAudio = false
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        AppLog.i("ui: requesting screen-capture consent for ${device.friendlyName}")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    private fun onStopMirroring() {
        AppLog.i("ui: stop requested")
        val service = boundService
        if (service != null) service.stopSession(getString(R.string.status_stopped_by_user))
        else startService(MirrorService.stopIntent(this))
    }

    private fun onProbe() {
        val device = selectedDevice() ?: run {
            toast(getString(R.string.error_no_device))
            return
        }
        persistBitrate()
        val rung = settings.appIdMode.ladder(device.hasVideoOutput()).first()
        probeInFlight = true
        updateButtons()
        statusView.text = getString(R.string.status_probing, device.friendlyName)
        ProbeRunner.run(
            device = device,
            rung = rung,
            appPackage = packageName,
            // The session offers the fixed landscape frame, so the probe does too.
            resolution = settings.resolution.landscape(),
        ) { ok, summary ->
            runOnUiThread {
                probeInFlight = false
                updateButtons()
                statusView.text = if (ok) getString(R.string.status_probe_ok, summary)
                else getString(R.string.status_probe_failed, summary)
            }
        }
    }

    /**
     * Receives the screen-capture consent result.
     *
     * The replacement Activity Result APIs live in AndroidX, which this app deliberately does not
     * depend on, so the deprecated framework callback is the only option available.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            AppLog.w("ui: screen-capture consent was denied")
            toast(getString(R.string.error_consent_denied))
            return
        }
        val device = selectedDevice() ?: return
        // The moment a session is actually launched, this device is the authoritative last-used
        // target; persist it so a later cold start restores exactly this pick.
        applySelection(keyOf(device), persist = true, reRender = false)
        AppLog.i("ui: starting a session with ${device.friendlyName} (${device.host}:${device.port})")
        startForegroundService(
            MirrorService.startIntent(
                context = this,
                host = device.host,
                port = device.port,
                deviceName = device.friendlyName,
                hasVideoOutput = device.hasVideoOutput(),
                resultCode = resultCode,
                resultData = data,
            )
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_NOTIFICATIONS -> {
                if (!granted) AppLog.w("ui: notification permission denied; the session can still run but has no Stop button")
                if (startAfterPermission) {
                    startAfterPermission = false
                    beginSession()
                }
            }
            REQUEST_RECORD_AUDIO -> {
                // Proceed either way: audio granted → captured; denied → video-only (the service
                // re-checks the permission and logs the outcome).
                if (!granted) AppLog.w("ui: RECORD_AUDIO denied; mirroring will be video only")
                beginSession()
            }
        }
    }

    // ---- rendering ----------------------------------------------------------------------------

    /**
     * Reconciles the radio group with the current device list, in place.
     *
     * Rows are keyed by device address and given a stable View id, so a periodic discovery pass
     * updates existing rows (and their text, as an mDNS follow-up fills in the model) and appends
     * new ones rather than tearing the group down and rebuilding it. The selection survives every
     * pass: it is stored as [selectedDeviceKey], the selected device is kept in the list even if a
     * pass drops it, and the check state is re-applied after the structural changes with the
     * listener suppressed.
     */
    private fun renderDevices() {
        devices.forEach { lastKnownByKey[keyOf(it)] = it }

        val merged = LinkedHashMap<String, CastDevice>()
        devices.forEach { merged[keyOf(it)] = it }
        selectedDeviceKey?.let { key ->
            if (!merged.containsKey(key)) lastKnownByKey[key]?.let { merged[key] = it }
        }
        val desired = merged.values.toList()
        val desiredKeys = merged.keys

        emptyDevicesView.visibility = if (desired.isEmpty()) View.VISIBLE else View.GONE

        suppressCheckListener = true

        deviceRowIds.keys.filter { it !in desiredKeys }.forEach { key ->
            val id = deviceRowIds.remove(key) ?: return@forEach
            deviceGroup.findViewById<RadioButton>(id)?.let { deviceGroup.removeView(it) }
        }

        desired.forEach { device ->
            val key = keyOf(device)
            val existingId = deviceRowIds[key]
            val button: RadioButton? = if (existingId != null) {
                deviceGroup.findViewById(existingId)
            } else {
                RadioButton(this).apply {
                    id = View.generateViewId()
                    // Do not let the framework restore this button's checked state by id; the
                    // Activity re-applies the selection from the persisted device key instead.
                    isSaveEnabled = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    deviceRowIds[key] = this.id
                    deviceGroup.addView(this)
                }
            }
            button?.text = describe(device)
        }

        if (selectedDeviceKey == null && desired.size == 1) selectedDeviceKey = keyOf(desired[0])

        val selectedId = selectedDeviceKey?.let { deviceRowIds[it] }
        if (selectedId != null) {
            if (deviceGroup.checkedRadioButtonId != selectedId) deviceGroup.check(selectedId)
        } else {
            deviceGroup.clearCheck()
        }

        suppressCheckListener = false
        updateButtons()
    }

    private fun describe(device: CastDevice): String {
        val model = device.model ?: getString(R.string.device_unknown_model)
        val capability = if (device.hasVideoOutput()) getString(R.string.device_video)
        else getString(R.string.device_audio_only)
        return "${device.friendlyName}\n$model · ${device.host}:${device.port} · $capability"
    }

    private fun renderStatus(status: MirrorStatus) {
        runOnUiThread {
            reconcileWithService(status)
            statusView.text = status.detail
            val stats = status.stats
            if (stats.isNullOrEmpty()) {
                statsView.visibility = View.GONE
            } else {
                statsView.visibility = View.VISIBLE
                statsView.text = stats
            }
            updateButtons()
        }
    }

    private fun renderLog() {
        logView.text = AppLog.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateButtons() {
        val running = MirrorService.isRunning
        val hasDevice = selectedDevice() != null
        startButton.isEnabled = hasDevice && !running && !probeInFlight
        stopButton.isEnabled = running
        probeButton.isEnabled = hasDevice && !running && !probeInFlight
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun selectedDevice(): CastDevice? =
        selectedDeviceKey?.let { key -> devices.firstOrNull { keyOf(it) == key } }

    /**
     * Sets the selection by key, the one place that mutates [selectedDeviceKey].
     *
     * @param key the device's `host:port`, or `null` to clear.
     * @param persist whether to record it as the durable last-used pick. Deliberate picks and the
     *   actual streaming target persist; a transient reconcile need not.
     * @param reRender whether to re-run [renderDevices] to re-check the row. The tap path is
     *   already visually checked and skips it; programmatic restores request it.
     */
    private fun applySelection(key: String?, persist: Boolean, reRender: Boolean) {
        val changed = key != selectedDeviceKey
        selectedDeviceKey = key
        if (persist) persistSelection(key)
        if (reRender && changed) renderDevices() else updateButtons()
    }

    private fun persistSelection(key: String?) {
        selectionPrefs.edit().apply {
            if (key == null) remove(KEY_SELECTED_DEVICE) else putString(KEY_SELECTED_DEVICE, key)
        }.apply()
    }

    /**
     * Aligns the shown selection with a [MirrorService] status.
     *
     * While a session is active the streaming target is authoritative: the UI must show that exact
     * device, never a different one restored from stale state. When idle, a known last target is
     * adopted only if nothing is selected, so it never overrides a fresh user pick.
     */
    private fun reconcileWithService(status: MirrorStatus) {
        val serviceKey = status.deviceKey ?: return
        if (status.active) {
            if (serviceKey != selectedDeviceKey) {
                AppLog.i("ui: reconciling selection to the active session target $serviceKey")
                applySelection(serviceKey, persist = true, reRender = true)
            }
        } else if (selectedDeviceKey == null) {
            applySelection(serviceKey, persist = true, reRender = true)
        }
    }

    private fun keyOf(device: CastDevice): String = "${device.host}:${device.port}"

    /** Splits `host`, `host:port` or `[v6]:port` into its parts, defaulting to the Cast port. */
    private fun splitHostPort(entry: String): Pair<String, Int> {
        if (entry.startsWith("[")) {
            val close = entry.indexOf(']')
            if (close > 0) {
                val host = entry.substring(1, close)
                val port = entry.substring(close + 1).removePrefix(":").toIntOrNull()
                return host to (port ?: DeviceDiscovery.DEFAULT_CAST_PORT)
            }
        }
        val colon = entry.lastIndexOf(':')
        if (colon > 0 && entry.indexOf(':') == colon) {
            val port = entry.substring(colon + 1).toIntOrNull()
            if (port != null) return entry.substring(0, colon) to port
        }
        return entry to DeviceDiscovery.DEFAULT_CAST_PORT
    }

    private fun persistBitrate() {
        val value = bitrateField.text.toString().trim().toIntOrNull() ?: return
        val clamped = value.coerceIn(MirrorSettings.MIN_BITRATE_KBPS, MirrorSettings.MAX_BITRATE_KBPS)
        settings.bitrateKbps = clamped
        if (clamped != value) bitrateField.setText(clamped.toString())
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        alpha = 0.7f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun buttonRow(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { child ->
            child.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addView(child)
        }
    }

    private fun labelled(label: String, field: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@MainActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(dp(120), WRAP)
            }
        )
        addView(field)
    }

    /**
     * A labelled [Spinner] that reports the chosen index.
     *
     * @param onSelected invoked with the new index, including once during layout with the initial
     *   selection — the writes are idempotent, so re-persisting the current value is harmless.
     */
    private fun spinnerRow(
        label: String,
        entries: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ): View {
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, entries).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selectedIndex.coerceIn(0, entries.size - 1))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    onSelected(position)

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        return labelled(label, spinner)
    }

    private companion object {
        const val REQUEST_PROJECTION = 1001
        const val REQUEST_NOTIFICATIONS = 1002
        const val REQUEST_RECORD_AUDIO = 1003
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** SharedPreferences key for the durable last-used device selection (`host:port`). */
        const val KEY_SELECTED_DEVICE = "selected_device_key"

        /** Saved-instance-state key for the selection across a config change. */
        const val STATE_SELECTED_DEVICE = "selected_device_key"
    }
}
