package home.screen_to_chromecast

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import home.screen_to_chromecast.casting.ScreenCastingService
import home.screen_to_chromecast.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.util.VLCUtil

class MainActivity : AppCompatActivity(), RendererDiscoverer.EventListener {

    private lateinit var binding: ActivityMainBinding
    private var libVLC: ILibVLC? = null
    private var rendererDiscoverer: RendererDiscoverer? = null
    // Store RendererItem directly. Lifecycle management might be automatic in 3.6.2 or different.
    private val discoveredRenderers = ArrayList<RendererItem>()
    private lateinit var rendererAdapter: ArrayAdapter<String>
    private var selectedRenderer: RendererItem? = null

    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection permission granted.")
                val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                    action = ScreenCastingService.ACTION_START_CASTING
                    putExtra(ScreenCastingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCastingService.EXTRA_RESULT_DATA, result.data)
                    // selectedRenderer is set in RendererHolder by setOnItemClickListener
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
                RendererHolder.selectedRendererItem = null
                selectedRenderer = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.getRoot())

        setupToolbar()
        setupListView()

        if (!VLCUtil.hasCompatibleCPU(this)) {
            Log.e(TAG, "Device CPU is not compatible with LibVLC.")
            binding.textViewStatus.text = getString(R.string.error_prefix) + "Device not compatible with LibVLC."
            return
        }

        val libVlcArgs = ArrayList<String>()
        libVlcArgs.add("--no-sub-autodetect-file")
        try {
            libVLC = LibVLC(this, libVlcArgs)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error initializing LibVLC: ${e.localizedMessage}", e)
            binding.textViewStatus.text = getString(R.string.error_prefix) + "Error initializing LibVLC."
            return
        }
        startDiscovery()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupListView() {
        rendererAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        binding.listViewDevices.adapter = rendererAdapter
        binding.listViewDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < discoveredRenderers.size) {
                val clickedRenderer = discoveredRenderers[position]

                selectedRenderer = clickedRenderer
                RendererHolder.selectedRendererItem = clickedRenderer

                // Try direct property access for iconUri, then a getter as fallback
                val iconInfo = clickedRenderer.iconUri ?: try { clickedRenderer.getString("iconUri") } catch (e: Exception) { null } ?: "no_icon"
                Log.d(TAG, "Selected renderer: ${clickedRenderer.name} (Type: ${clickedRenderer.type}, Icon: $iconInfo)")
                binding.textViewStatus.text = getString(R.string.casting_to, clickedRenderer.displayName ?: clickedRenderer.name)

                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                requestMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun startDiscovery() {
        libVLC?.let { vlc ->
            if (rendererDiscoverer == null) {
                rendererDiscoverer = RendererDiscoverer(vlc, "microdns_renderer")
            }
            rendererDiscoverer?.setEventListener(this@MainActivity)
            if (rendererDiscoverer?.start() == true) {
                Log.d(TAG, "Renderer discovery started.")
                binding.textViewStatus.text = getString(R.string.discovering_devices)
            } else {
                Log.e(TAG, "Failed to start renderer discovery. Discoverer: $rendererDiscoverer, VLC: $vlc")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Failed to start discovery."
            }
        } ?: run {
            Log.e(TAG, "LibVLC instance is null, cannot start discovery.")
            binding.textViewStatus.text = getString(R.string.error_prefix) + "LibVLC not initialized."
        }
    }

    private fun stopDiscovery() {
        rendererDiscoverer?.let {
            it.setEventListener(null)
            it.stop()
            Log.d(TAG, "Renderer discovery stopped.")
        }
    }

    override fun onEvent(event: RendererDiscoverer.Event) {
        val item = event.item

        // Compare event.type with integer constants if that's how LibVLC 3.6.2 defines them
        when (event.type) {
            RendererDiscoverer.Event.ITEM_ADDED -> { // Assuming ITEM_ADDED is a public static final int
                item ?: return
                Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type}, DisplayName: ${item.displayName})")
                // No explicit item.hold()
                synchronized(discoveredRenderers) {
                    if (!discoveredRenderers.any { it.name == item.name }) {
                        discoveredRenderers.add(item)
                    }
                }
                updateRendererListUI()
            }
            RendererDiscoverer.Event.ITEM_DELETED -> { // Assuming ITEM_DELETED is a public static final int
                item ?: return
                Log.d(TAG, "Renderer Removed: ${item.name} (Type: ${item.type})")
                synchronized(discoveredRenderers) {
                    discoveredRenderers.removeAll { it.name == item.name }
                }
                // No explicit item.release()

                if (selectedRenderer?.name == item.name) {
                    Log.d(TAG, "Selected renderer was removed: ${item.name}")
                    val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                        action = ScreenCastingService.ACTION_STOP_CASTING
                    }
                    startService(serviceIntent)
                    selectedRenderer = null
                    if (RendererHolder.selectedRendererItem?.name == item.name) {
                        RendererHolder.selectedRendererItem = null
                    }
                    binding.textViewStatus.text = getString(R.string.casting_stopped)
                }
                updateRendererListUI()
            }
            else -> {
                // Log.d(TAG, "RendererDiscoverer Event: type=${event.type}")
            }
        }
    }

    private fun updateRendererListUI() {
        lifecycleScope.launch {
            val rendererNames = synchronized(discoveredRenderers) {
                discoveredRenderers.map { it.displayName ?: it.name ?: "Unknown Renderer" }
            }
            rendererAdapter.clear()
            if (rendererNames.isEmpty() && selectedRenderer == null) {
                binding.textViewStatus.text = getString(R.string.no_devices_found)
            } else if (selectedRenderer == null) {
                binding.textViewStatus.text = getString(R.string.select_device_to_cast)
            }
            rendererAdapter.addAll(rendererNames)
            rendererAdapter.notifyDataSetChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        if (libVLC != null) {
            if (rendererDiscoverer == null) {
                startDiscovery()
            } else {
                // Try isStarted() as a method call
                val isDiscStarted = rendererDiscoverer?.isStarted() ?: false
                if (!isDiscStarted) {
                    rendererDiscoverer?.setEventListener(this@MainActivity)
                    if (rendererDiscoverer?.start() == false) { // Check return of start
                         Log.e(TAG, "Failed to restart renderer discovery in onResume.")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            stopDiscovery()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        rendererDiscoverer = null
        // No explicit release for items, assuming LibVLC 3.6.2 manages them or they are GC'd
        discoveredRenderers.clear()
        selectedRenderer = null
        RendererHolder.selectedRendererItem = null
        libVLC?.release()
        libVLC = null
        Log.d(TAG, "MainActivity onDestroy: LibVLC released.")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

object RendererHolder {
    var selectedRendererItem: RendererItem? = null
}
