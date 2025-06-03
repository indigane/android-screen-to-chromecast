package home.screen_to_chromecast

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
import org.videolan.libvlc.util.VLCUtil

class MainActivity : AppCompatActivity(), LibVLC.RendererDiscoverer.Listener {

    private lateinit var binding: ActivityMainBinding
    private var libVLC: LibVLC? = null
    private var rendererDiscoverer: LibVLC.RendererDiscoverer? = null
    private val discoveredRenderers = ArrayList<RendererItem>()
    private lateinit var rendererAdapter: ArrayAdapter<String>
    private var selectedRenderer: RendererItem? = null

    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection permission granted.")
                val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                    action = ScreenCastingService.ACTION_START_CASTING
                    putExtra(ScreenCastingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCastingService.EXTRA_RESULT_DATA, result.data)
                    selectedRenderer?.let {
                        // Passing name as a simple identifier.
                        // The service will need a robust way to use this.
                        putExtra(ScreenCastingService.EXTRA_RENDERER_NAME, it.name)
                        // Store the selected item for the service to potentially access
                        RendererHolder.selectedRendererItem = it
                    }
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
                RendererHolder.selectedRendererItem = null // Clear if permission denied
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
        // libVlcArgs.add("-vvv") // For verbose logging, useful for debugging
        libVlcArgs.add("--no-sub-autodetect-file")
        libVLC = LibVLC(this, libVlcArgs)

        startDiscovery()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        // If you want to set a title:
        // supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupListView() {
        rendererAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        binding.listViewDevices.adapter = rendererAdapter
        binding.listViewDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < discoveredRenderers.size) {
                val clickedRenderer = discoveredRenderers[position]
                // It's crucial to hold the RendererItem if we are going to use it later,
                // especially if passing its reference or details to a service.
                // LibVLC reuses RendererItem objects, so their internal pointers can become invalid
                // if not properly managed (held when needed, released when done).
                // For now, we are passing the name and storing it in RendererHolder.
                // A more robust solution might involve cloning necessary data or proper ref counting.
                clickedRenderer.hold() // Hold the item before storing/passing
                selectedRenderer = clickedRenderer // Keep a reference in activity too
                RendererHolder.selectedRendererItem = clickedRenderer // Make it accessible to service

                Log.d(TAG, "Selected renderer: ${clickedRenderer.name} (Type: ${clickedRenderer.type}, Icon: ${clickedRenderer.iconUri})")
                binding.textViewStatus.text = getString(R.string.casting_to, clickedRenderer.displayName ?: clickedRenderer.name)

                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                requestMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun startDiscovery() {
        libVLC?.let { vlc ->
            if (rendererDiscoverer == null) {
                rendererDiscoverer = LibVLC.RendererDiscoverer(vlc, "microdns_renderer").apply {
                    setListener(this@MainActivity)
                }
            }
            if (rendererDiscoverer?.start() == true) {
                Log.d(TAG, "Renderer discovery started.")
                binding.textViewStatus.text = getString(R.string.discovering_devices)
            } else {
                Log.e(TAG, "Failed to start renderer discovery.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Failed to start discovery."
            }
        } ?: run {
            Log.e(TAG, "LibVLC instance is null, cannot start discovery.")
            binding.textViewStatus.text = getString(R.string.error_prefix) + "LibVLC not initialized."
        }
    }

    private fun stopDiscovery() {
        rendererDiscoverer?.let {
            it.stop()
            // According to some LibVLC examples/docs, the discoverer itself might not need explicit release,
            // but items it discovers do (if held). The discoverer is tied to the LibVLC instance.
            // For safety, nullifying the listener.
            it.setListener(null)
            Log.d(TAG, "Renderer discovery stopped.")
        }
        rendererDiscoverer = null // Allow it to be recreated if needed
    }

    override fun onRendererAdded(discoverer: LibVLC.RendererDiscoverer, item: RendererItem) {
        Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type}, DisplayName: ${item.displayName})")
        // RendererItem objects are recycled by LibVLC. If you need to store them
        // or use them outside this callback (e.g. pass to another thread/service),
        // you MUST call item.hold() and item.release() when done.
        // For adding to a list that's displayed, holding is good practice.
        item.hold()
        synchronized(discoveredRenderers) {
            if (!discoveredRenderers.any { it.name == item.name }) {
                discoveredRenderers.add(item)
            }
        }
        updateRendererListUI()
    }

    override fun onRendererRemoved(discoverer: LibVLC.RendererDiscoverer, item: RendererItem) {
        Log.d(TAG, "Renderer Removed: ${item.name} (Type: ${item.type})")
        var rendererToRelease: RendererItem? = null
        synchronized(discoveredRenderers) {
            val iterator = discoveredRenderers.iterator()
            while (iterator.hasNext()) {
                val existingItem = iterator.next()
                if (existingItem.name == item.name) {
                    iterator.remove()
                    rendererToRelease = existingItem // This is the item we held
                    break
                }
            }
        }
        rendererToRelease?.release() // Release the item we previously held

        if (selectedRenderer?.name == item.name) {
            Log.d(TAG, "Selected renderer was removed: ${item.name}")
            val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                action = ScreenCastingService.ACTION_STOP_CASTING
            }
            startService(serviceIntent)
            selectedRenderer?.release() // Release the selected renderer if it's this one
            selectedRenderer = null
            RendererHolder.selectedRendererItem = null
            binding.textViewStatus.text = getString(R.string.casting_stopped)
        }
        updateRendererListUI()
    }

    private fun updateRendererListUI() {
        lifecycleScope.launch {
            val rendererNames = synchronized(discoveredRenderers) {
                discoveredRenderers.map { it.displayName ?: it.name }
            }
            rendererAdapter.clear()
            if (rendererNames.isEmpty() && selectedRenderer == null) { // Check selectedRenderer as well
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
        if (libVLC != null && rendererDiscoverer?.isStarted != true) {
             startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        // Decide on discovery strategy. Stopping here might be too aggressive
        // if user briefly switches apps. For now, let discovery run if app is paused but not destroyed.
        // stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        synchronized(discoveredRenderers) {
            discoveredRenderers.forEach { it.release() } // Release all held items
            discoveredRenderers.clear()
        }
        selectedRenderer?.release() // Release if still holding
        selectedRenderer = null
        RendererHolder.selectedRendererItem = null // Clear the static holder

        libVLC?.release()
        libVLC = null
        Log.d(TAG, "MainActivity onDestroy: LibVLC released.")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

// Simple static holder for the selected RendererItem.
// This is a basic way to pass the item to the service.
// Consider a more robust solution like a Bound Service or a ViewModel if complexity grows.
object RendererHolder {
    var selectedRendererItem: RendererItem? = null
}
