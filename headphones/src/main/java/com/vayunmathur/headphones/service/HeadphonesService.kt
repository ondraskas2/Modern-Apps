package com.vayunmathur.headphones.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vayunmathur.headphones.HeadphonesState
import com.vayunmathur.headphones.R
import com.vayunmathur.headphones.bluetooth.BondedDeviceFinder
import com.vayunmathur.headphones.bluetooth.ConnectionState
import com.vayunmathur.headphones.bluetooth.RfcommManager
import com.vayunmathur.headphones.protocol.CommandBuilders
import com.vayunmathur.headphones.protocol.NcAsmMode
import com.vayunmathur.headphones.protocol.SonyMessage
import com.vayunmathur.headphones.protocol.SonyResponse
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that owns the [RfcommManager] connection, auto-reconnects with
 * backoff, runs the initial state queries on connect, folds parsed responses into a single
 * [HeadphonesState], and surfaces it through a companion `StateFlow` (the same pattern as
 * `SyncForegroundService`) so the UI can observe and command across recomposition.
 */
class HeadphonesService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: RfcommManager
    private lateinit var finder: BondedDeviceFinder
    private lateinit var ds: DataStoreUtils

    private var reconnectJob: Job? = null

    @Volatile
    private var targetAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager = RfcommManager()
        finder = BondedDeviceFinder(this)
        ds = DataStoreUtils.getInstance(this)
        targetAddress = ds.getString(PREF_ADDRESS)?.takeIf { it.isNotBlank() }

        scope.launch {
            manager.state.collect { conn ->
                _state.update { it.copy(connection = conn) }
                updateNotification()
                when (conn) {
                    ConnectionState.Connected -> onConnected()
                    ConnectionState.Disconnected -> scheduleReconnect()
                    ConnectionState.Connecting -> {}
                }
            }
        }
        scope.launch { manager.responses.collect(::onResponse) }
        scope.launch {
            manager.traffic.collect { entry ->
                _traffic.tryEmit(entry)
                _trafficLog.value = (listOf(entry) + _trafficLog.value).take(MAX_TRAFFIC_LOG)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        intent?.getStringExtra(EXTRA_ADDRESS)?.let { address ->
            targetAddress = address
            scope.launch { ds.setString(PREF_ADDRESS, address) }
        }
        attemptConnect()
        return START_STICKY
    }

    private fun attemptConnect() {
        val address = targetAddress ?: return
        if (manager.state.value != ConnectionState.Disconnected) return
        val device = finder.deviceFor(address) ?: return
        _state.update { it.copy(deviceName = device.name ?: address, deviceAddress = address) }
        manager.connect(device)
    }

    private fun scheduleReconnect() {
        if (targetAddress == null) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = INITIAL_BACKOFF_MS
            while (manager.state.value == ConnectionState.Disconnected && targetAddress != null) {
                delay(delayMs)
                attemptConnect()
                delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** On connect, query the device for its capabilities and current state. */
    private fun onConnected() {
        reconnectJob?.cancel()
        manager.send(CommandBuilders.getCapabilities())
        manager.send(CommandBuilders.getDualBattery())
        manager.send(CommandBuilders.getCaseBattery())
        manager.send(CommandBuilders.getSingleBattery())
        manager.send(CommandBuilders.getNcAsm())
        manager.send(CommandBuilders.getEq())
        manager.send(CommandBuilders.subscribePairedDevices())
    }

    private fun onResponse(response: SonyResponse) {
        _state.update { current ->
            when (response) {
                is SonyResponse.Battery -> current.copy(battery = mergeBattery(current.battery, response))
                is SonyResponse.NcAsm -> current.copy(ncAsm = response.state)
                is SonyResponse.Eq -> current.copy(eqPreset = response.preset, eqBands = response.bands)
                is SonyResponse.Capabilities -> current.copy(capabilities = response.capabilities)
                is SonyResponse.SimpleFeature ->
                    current.copy(features = current.features + (response.toggle.feature to response.toggle.enabled))
                is SonyResponse.PairedDevices -> current.copy(pairedDevices = response.devices)
                is SonyResponse.Ack, is SonyResponse.Raw -> current
            }
        }
        updateNotification()
    }

    private fun mergeBattery(
        current: com.vayunmathur.headphones.protocol.BatteryInfo,
        response: SonyResponse.Battery,
    ): com.vayunmathur.headphones.protocol.BatteryInfo {
        val incoming = response.info
        return current.copy(
            single = if (incoming.single.isKnown) incoming.single else current.single,
            left = if (incoming.left.isKnown) incoming.left else current.left,
            right = if (incoming.right.isKnown) incoming.right else current.right,
            case = if (incoming.case.isKnown) incoming.case else current.case,
        )
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        val s = _state.value
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(s.deviceName ?: getString(R.string.notification_title))
            .setContentText(notificationText(s))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun notificationText(s: HeadphonesState): String {
        if (!s.isConnected) return getString(R.string.notification_disconnected)
        val parts = mutableListOf<String>()
        val b = s.battery
        when {
            b.left.isKnown || b.right.isKnown -> {
                if (b.left.isKnown) parts += "L ${b.left.level}%"
                if (b.right.isKnown) parts += "R ${b.right.level}%"
                if (b.case.isKnown) parts += "Case ${b.case.level}%"
            }
            b.single.isKnown -> parts += "${b.single.level}%"
        }
        parts += when (s.ncAsm.mode) {
            NcAsmMode.OFF -> "Off"
            NcAsmMode.NOISE_CANCELLING -> "Noise Cancelling"
            NcAsmMode.AMBIENT_SOUND -> "Ambient"
        }
        return parts.joinToString(" · ")
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        manager.release()
        scope.cancel()
        instance = null
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "headphones_connection"
        private const val NOTIFICATION_ID = 1
        private const val PREF_ADDRESS = "headphones_device_address"
        private const val EXTRA_ADDRESS = "address"
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L

        @Volatile
        private var instance: HeadphonesService? = null

        private val _state = MutableStateFlow(HeadphonesState())
        val state: StateFlow<HeadphonesState> = _state

        private val _traffic = MutableSharedFlow<RfcommManager.TrafficEntry>(extraBufferCapacity = 256)
        val traffic: SharedFlow<RfcommManager.TrafficEntry> = _traffic

        /** Retained rolling log so the Debug page shows frames captured before it was opened. */
        private val _trafficLog = MutableStateFlow<List<RfcommManager.TrafficEntry>>(emptyList())
        val trafficLog: StateFlow<List<RfcommManager.TrafficEntry>> = _trafficLog
        private const val MAX_TRAFFIC_LOG = 300

        /** Persists [address] and (re)starts the service pointed at that device. */
        fun connectTo(context: Context, address: String) {
            val intent = Intent(context, HeadphonesService::class.java).putExtra(EXTRA_ADDRESS, address)
            context.startForegroundService(intent)
        }

        fun start(context: Context) =
            context.startForegroundService(Intent(context, HeadphonesService::class.java))

        /** Starts the service only if a device was previously chosen (so no idle notification). */
        fun startIfConfigured(context: Context) {
            val saved = DataStoreUtils.getInstance(context).getString(PREF_ADDRESS)
            if (!saved.isNullOrBlank()) start(context)
        }

        fun stop(context: Context) =
            context.stopService(Intent(context, HeadphonesService::class.java))

        /** Forgets the saved device and stops the connection. */
        fun forget(context: Context) {
            val svc = instance
            svc?.targetAddress = null
            svc?.scope?.launch { svc.ds.setString(PREF_ADDRESS, "") }
            svc?.manager?.disconnect()
            _state.value = HeadphonesState()
            stop(context)
        }

        /** Sends a command to the live connection (no-op if disconnected). */
        fun send(message: SonyMessage) {
            instance?.manager?.send(message)
        }
    }
}
