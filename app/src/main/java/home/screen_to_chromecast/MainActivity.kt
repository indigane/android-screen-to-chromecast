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
import org.videolan.libvlc.LibVLC // Main LibVLC class
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.RendererDiscoverer // Import RendererDiscoverer
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.util.VLCUtil

// Implement the correct EventListener from LibVLC
class MainActivity : AppCompatActivity(), RendererDiscoverer.EventListener {

    private lateinit var binding: ActivityMainBinding
    private var libVLC: ILibVLC? = null
    private var rendererDiscoverer: RendererDiscoverer? = null
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
                    // selectedRenderer is already held and set in RendererHolder by this point
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
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
        libVlcArgs.add("--no-sub-autodetect-file")
        // libVlcArgs.add("-vvv") // For verbose logging
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

                if (selectedRenderer != null && selectedRenderer != clickedRenderer) {
                    selectedRenderer?.release()
                }
                if (RendererHolder.selectedRendererItem != null && RendererHolder.selectedRendererItem != clickedRenderer) {
                    RendererHolder.selectedRendererItem?.release()
                }

                clickedRenderer.hold() // Hold the new item
                selectedRenderer = clickedRenderer
                RendererHolder.selectedRendererItem = clickedRenderer

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
        // Don't nullify rendererDiscoverer here, so isStarted can be checked
    }

    override fun onEvent(event: RendererDiscoverer.Event) {
        val item = event.item // event.item can be null for some event types
        // It's safer to check item for nullity before using it, especially for ItemDeleted

        when (event.type) {
            RendererDiscoverer.Event.Type.ItemAdded -> {
                item ?: return // If item is null for ItemAdded, something is wrong, ignore.
                Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type}, DisplayName: ${item.displayName})")
                item.hold()
                synchronized(discoveredRenderers) {
                    if (!discoveredRenderers.any { it.name == item.name }) {
                        discoveredRenderers.add(item)
                    } else {
                        item.release() // Already have it, release the new instance
                    }
                }
                updateRendererListUI()
            }
            RendererDiscoverer.Event.Type.ItemDeleted -> {
                item ?: return // If item is null for ItemDeleted, we can't identify what was deleted.
                Log.d(TAG, "Renderer Removed: ${item.name} (Type: ${item.type})")
                var rendererToRelease: RendererItem? = null
                synchronized(discoveredRenderers) {
                    val iterator = discoveredRenderers.iterator()
                    while (iterator.hasNext()) {
                        val existingItem = iterator.next()
                        if (existingItem.name == item.name) {
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
                    startService(serviceIntent)
                    selectedRenderer = null // Already released by rendererToRelease logic
                    if (RendererHolder.selectedRendererItem?.name == item.name) {
                        RendererHolder.selectedRendererItem?.release() // Release from holder
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
        if (libVLC != null) {
            if (rendererDiscoverer == null) {
                startDiscovery()
            } else if (rendererDiscoverer?.isStarted == false) { // isStarted is a property
                rendererDiscoverer?.setEventListener(this@MainActivity)
                if (rendererDiscoverer?.start() == false) { // Check return of start
                     Log.e(TAG, "Failed to restart renderer discovery in onResume.")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            stopDiscovery()
        }
        // else: Consider if discovery should be stopped/paused if activity is just paused.
        // For now, let it run to maintain an updated list if user returns quickly.
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        rendererDiscoverer = null // Safe to nullify here

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
    // No custom setter needed if MainActivity manages release properly before setting a new one.
}
