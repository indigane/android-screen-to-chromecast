package home.screen_to_chromecast

import android.app.Activity // Import for RESULT_OK
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
import org.videolan.libvlc.LibVLC // Main LibVLC class
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.ILibVLC // For ILibVLC type if needed explicitly
import org.videolan.libvlc.util.VLCUtil

// Implement the correct Listener from LibVLC
class MainActivity : AppCompatActivity(), org.videolan.libvlc.RendererDiscoverer.Listener {

    private lateinit var binding: ActivityMainBinding
    private var libVLC: ILibVLC? = null // Use interface type
    private var rendererDiscoverer: org.videolan.libvlc.RendererDiscoverer? = null // Explicit type
    private val discoveredRenderers = ArrayList<RendererItem>()
    private lateinit var rendererAdapter: ArrayAdapter<String>
    private var selectedRenderer: RendererItem? = null

    private val requestMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) { // Use Activity.RESULT_OK
                Log.d(TAG, "MediaProjection permission granted.")
                val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                    action = ScreenCastingService.ACTION_START_CASTING
                    putExtra(ScreenCastingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCastingService.EXTRA_RESULT_DATA, result.data)
                    selectedRenderer?.let {
                        putExtra(ScreenCastingService.EXTRA_RENDERER_NAME, it.name)
                        // RendererHolder.selectedRendererItem = it // Already held by this point
                    }
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
                // If permission is denied, release the renderer we might have held
                selectedRenderer?.release()
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
        // libVlcArgs.add("-vvv")
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

                // Release previously selected (if any and different)
                if (selectedRenderer != null && selectedRenderer != clickedRenderer) {
                    selectedRenderer?.release()
                }
                RendererHolder.selectedRendererItem?.release() // Release whatever was in holder

                clickedRenderer.hold() // Hold the new one
                selectedRenderer = clickedRenderer
                RendererHolder.selectedRendererItem = clickedRenderer

                Log.d(TAG, "Selected renderer: ${clickedRenderer.name} (Type: ${clickedRenderer.type}, Icon: ${clickedRenderer.icon_uri})") // Use icon_uri
                binding.textViewStatus.text = getString(R.string.casting_to, clickedRenderer.displayName ?: clickedRenderer.name)

                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                requestMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun startDiscovery() {
        libVLC?.let { vlc ->
            if (rendererDiscoverer == null) {
                // Use the fully qualified name for RendererDiscoverer if there's ambiguity
                rendererDiscoverer = org.videolan.libvlc.RendererDiscoverer(vlc, "microdns_renderer").apply {
                    setEventListener(this@MainActivity) // Corrected: use setEventListener
                }
            }
            if (rendererDiscoverer?.start() == true) { // Check for true explicitly
                Log.d(TAG, "Renderer discovery started.")
                binding.textViewStatus.text = getString(R.string.discovering_devices)
            } else {
                Log.e(TAG, "Failed to start renderer discovery. Discoverer: $rendererDiscoverer")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Failed to start discovery."
            }
        } ?: run {
            Log.e(TAG, "LibVLC instance is null, cannot start discovery.")
            binding.textViewStatus.text = getString(R.string.error_prefix) + "LibVLC not initialized."
        }
    }

    private fun stopDiscovery() {
        rendererDiscoverer?.let {
            it.setEventListener(null) // Remove listener first
            it.stop()
            Log.d(TAG, "Renderer discovery stopped.")
        }
        rendererDiscoverer = null
    }

    // Correct override signature from org.videolan.libvlc.RendererDiscoverer.Listener
    override fun onEvent(event: org.videolan.libvlc.RendererDiscoverer.Event) {
        when (event.type) {
            org.videolan.libvlc.RendererDiscoverer.Event.ItemAdded -> {
                val item = event.item ?: return
                Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type}, DisplayName: ${item.displayName})")
                item.hold()
                synchronized(discoveredRenderers) {
                    if (!discoveredRenderers.any { it.name == item.name }) {
                        discoveredRenderers.add(item)
                    } else {
                        item.release() // Release if it's a duplicate we won't add
                    }
                }
                updateRendererListUI()
            }
            org.videolan.libvlc.RendererDiscoverer.Event.ItemDeleted -> {
                val item = event.item ?: return
                Log.d(TAG, "Renderer Removed: ${item.name} (Type: ${item.type})")
                var rendererToRelease: RendererItem? = null
                synchronized(discoveredRenderers) {
                    val iterator = discoveredRenderers.iterator()
                    while (iterator.hasNext()) {
                        val existingItem = iterator.next()
                        if (existingItem.name == item.name) { // Compare by name, or a more robust ID if available
                            iterator.remove()
                            rendererToRelease = existingItem
                            break
                        }
                    }
                }
                rendererToRelease?.release()

                if (selectedRenderer?.name == item.name) {
                    Log.d(TAG, "Selected renderer was removed: ${item.name}")
                    val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                        action = ScreenCastingService.ACTION_STOP_CASTING
                    }
                    startService(serviceIntent) // Use startService for stopping ongoing service
                    selectedRenderer?.release() // Already released by rendererToRelease logic if it was in the list
                    selectedRenderer = null
                    RendererHolder.selectedRendererItem?.release() // Release from holder
                    RendererHolder.selectedRendererItem = null
                    binding.textViewStatus.text = getString(R.string.casting_stopped)
                }
                updateRendererListUI()
            }
            else -> {
                // Handle other event types if necessary
            }
        }
    }


    private fun updateRendererListUI() {
        lifecycleScope.launch {
            val rendererNames = synchronized(discoveredRenderers) {
                discoveredRenderers.map { it.displayName ?: it.name }
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
        if (libVLC != null && rendererDiscoverer?.isStarted != true) { // Check if discoverer is already started
            startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        // If activity is finishing, discovery will be stopped in onDestroy
        // Otherwise, let discovery continue if app is just paused.
        if (isFinishing) {
            stopDiscovery()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        synchronized(discoveredRenderers) {
            discoveredRenderers.forEach { it.release() }
            discoveredRenderers.clear()
        }
        selectedRenderer?.release()
        selectedRenderer = null
        RendererHolder.selectedRendererItem?.release()
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
        set(value) {
            // field?.release() // Release previous if any, careful with multiple releases
            field = value
        }
}
