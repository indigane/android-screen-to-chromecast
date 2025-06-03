package home.screen_to_chromecast

import android.content.Intent
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
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCUtil // For checking if LibVLC is supported

class MainActivity : AppCompatActivity(), LibVLC.RendererDiscoverer.Listener {

    private lateinit var binding: ActivityMainBinding
    private var libVLC: LibVLC? = null
    private var rendererDiscoverer: LibVLC.RendererDiscoverer? = null
    private val discoveredRenderers = ArrayList<RendererItem>()
    private lateinit var rendererAdapter: ArrayAdapter<String>
    private var selectedRenderer: RendererItem? = null

    // ActivityResultLauncher for MediaProjection permission
    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection permission granted.")
                // Pass the result data to the service
                val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                    action = ScreenCastingService.ACTION_START_CASTING
                    putExtra(ScreenCastingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCastingService.EXTRA_RESULT_DATA, result.data)
                    selectedRenderer?.let {
                        // Pass renderer details. RendererItem is not Parcelable.
                        // We might need to pass its name or other identifiers.
                        // For now, the service might need to re-discover or get it via a static reference (not ideal).
                        // Or, MainActivity can hold the selected RendererItem and service can access it.
                        // For simplicity in this initial step, we'll just start the service.
                        // The service will need a way to know which renderer to use.
                        putExtra(ScreenCastingService.EXTRA_RENDERER_NAME, it.name)
                        // Consider passing 'displayName' or 'type' if needed for re-discovery in service
                    }
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
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

        // Initialize LibVLC
        // LibVLC arguments: consider adding options for logging, etc.
        // e.g. arrayListOf("-vvv") for verbose logging
        libVLC = LibVLC(this, ArrayList<String>().apply { add("--no-sub-autodetect-file") }) // Basic options

        startDiscovery()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        // Further toolbar setup if needed (e.g., title)
    }

    private fun setupListView() {
        rendererAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        binding.listViewDevices.adapter = rendererAdapter
        binding.listViewDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < discoveredRenderers.size) {
                selectedRenderer = discoveredRenderers[position]
                selectedRenderer?.let {
                    Log.d(TAG, "Selected renderer: ${it.name} (Type: ${it.type}, Icon: ${it.iconUri})")
                    binding.textViewStatus.text = getString(R.string.casting_to, it.displayName)
                    // Request MediaProjection permission
                    // The actual MediaProjectionManager is now typically accessed via context.getSystemService()
                    val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    requestMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    private fun startDiscovery() {
        libVLC?.let { vlc ->
            // Create a renderer discoverer (e.g., for Chromecast)
            // "microdns_renderer" is commonly used for mDNS/Bonjour based discovery (Chromecast uses mDNS)
            rendererDiscoverer = LibVLC.RendererDiscoverer(vlc, "microdns_renderer").apply {
                setListener(this@MainActivity)
                if (!start()) {
                    Log.e(TAG, "Failed to start renderer discovery.")
                    binding.textViewStatus.text = getString(R.string.error_prefix) + "Failed to start discovery."
                } else {
                    Log.d(TAG, "Renderer discovery started.")
                    binding.textViewStatus.text = getString(R.string.discovering_devices)
                }
            }
        } ?: run {
            Log.e(TAG, "LibVLC instance is null, cannot start discovery.")
            binding.textViewStatus.text = getString(R.string.error_prefix) + "LibVLC not initialized."
        }
    }

    private fun stopDiscovery() {
        rendererDiscoverer?.let {
            if (it.stop()) {
                Log.d(TAG, "Renderer discovery stopped.")
            } else {
                Log.e(TAG, "Failed to stop renderer discovery.")
            }
            it.setListener(null) // Remove listener
            // it.release() // RendererDiscoverer does not have a release method directly, managed by LibVLC
        }
        rendererDiscoverer = null
    }

    override fun onRendererAdded(discoverer: LibVLC.RendererDiscoverer, item: RendererItem) {
        Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type})")
        // Filter for Chromecast devices if necessary, though "microdns_renderer" might already target them.
        // RendererItem.TYPE_CHROMECAST = 2 (Check LibVLC source/docs for correct type constant if available)
        // For now, add all discovered renderers.
        if (!discoveredRenderers.any { it.name == item.name }) { // Avoid duplicates by name
            // item.hold() // Hold the item if you plan to use it beyond this callback
            // Holding might be necessary if it's passed to another thread or stored long-term.
            // For now, we are just displaying names. If we pass the item to the service, it needs to be managed.
            discoveredRenderers.add(item)
            updateRendererListUI()
        }
    }

    override fun onRendererRemoved(discoverer: LibVLC.RendererDiscoverer, item: RendererItem) {
        Log.d(TAG, "Renderer Removed: ${item.name} (Type: ${item.type})")
        val removed = discoveredRenderers.removeAll { it.name == item.name }
        if (removed) {
            updateRendererListUI()
            if (selectedRenderer?.name == item.name) {
                Log.d(TAG, "Selected renderer was removed: ${item.name}")
                // Stop casting if this was the selected renderer
                val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                    action = ScreenCastingService.ACTION_STOP_CASTING
                }
                startService(serviceIntent) // Use startService for stopping
                selectedRenderer = null
                binding.textViewStatus.text = getString(R.string.casting_stopped)
            }
        }
        // item.release() // Release if it was held.
    }

    private fun updateRendererListUI() {
        lifecycleScope.launch { // Update UI on the main thread
            val rendererNames = discoveredRenderers.map { it.displayName ?: it.name } // Use displayName if available
            rendererAdapter.clear()
            if (rendererNames.isEmpty()) {
                binding.textViewStatus.text = getString(R.string.no_devices_found)
            } else if (selectedRenderer == null) { // Only update status if not casting
                binding.textViewStatus.text = getString(R.string.select_device_to_cast)
            }
            rendererAdapter.addAll(rendererNames)
            rendererAdapter.notifyDataSetChanged()
        }
    }


    override fun onResume() {
        super.onResume()
        // Restart discovery if it was stopped, or ensure it's running
        if (rendererDiscoverer == null && libVLC != null) {
            startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        // Consider stopping discovery when the activity is not visible to save resources,
        // but this might interrupt ongoing discovery if the user briefly navigates away.
        // For a casting app, continuous discovery might be desired while the app is in foreground.
        // stopDiscovery() // Decide based on desired UX
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        libVLC?.release() // Release the LibVLC instance
        libVLC = null
        Log.d(TAG, "MainActivity onDestroy: LibVLC released.")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
