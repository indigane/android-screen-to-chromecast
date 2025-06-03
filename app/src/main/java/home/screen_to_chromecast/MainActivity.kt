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
import org.videolan.libvlc.RendererDiscoverer // Import RendererDiscoverer
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.util.VLCUtil

// Implement the correct EventListener
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
                    // selectedRenderer is already held and set in RendererHolder
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
                selectedRenderer?.release() // Release if permission denied
                RendererHolder.selectedRendererItem = null // Clear holder too
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

                // Release previously selected (if any and different from current click)
                if (selectedRenderer != null && selectedRenderer != clickedRenderer) {
                    selectedRenderer?.release()
                }
                // Release item currently in holder if it's different from new selection or if holder had one
                if (RendererHolder.selectedRendererItem != null && RendererHolder.selectedRendererItem != clickedRenderer) {
                    RendererHolder.selectedRendererItem?.release()
                }


                clickedRenderer.hold() // Hold the new one
                selectedRenderer = clickedRenderer
                RendererHolder.selectedRendererItem = clickedRenderer // Update holder

                Log.d(TAG, "Selected renderer: ${clickedRenderer.name} (Type: ${clickedRenderer.type}, Icon: ${clickedRenderer.iconUri})") // iconUri is correct
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
            rendererDiscoverer?.setEventListener(this@MainActivity) // Set listener here
            if (rendererDiscoverer?.start() == true) {
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
            it.setEventListener(null)
            it.stop()
            Log.d(TAG, "Renderer discovery stopped.")
        }
        // Do not nullify rendererDiscoverer here, so isStarted can be checked in onResume
    }

    // This is the correct method to override from RendererDiscoverer.EventListener
    override fun onEvent(event: RendererDiscoverer.Event) {
        val item = event.item ?: return // Item can be null for some events

        when (event.type) {
            RendererDiscoverer.Event.Type.ItemAdded -> {
                Log.d(TAG, "Renderer Added: ${item.name} (Type: ${item.type}, DisplayName: ${item.displayName})")
                item.hold() // Hold the item as we are adding it to our list
                synchronized(discoveredRenderers) {
                    if (!discoveredRenderers.any { it.name == item.name }) {
                        discoveredRenderers.add(item)
                    } else {
                        item.release() // Release if it's a duplicate
                    }
                }
                updateRendererListUI()
            }
            RendererDiscoverer.Event.Type.ItemDeleted -> {
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
                    // selectedRenderer is already released by rendererToRelease logic if it was in the list
                    selectedRenderer = null
                    if (RendererHolder.selectedRendererItem?.name == item.name) {
                        RendererHolder.selectedRendererItem?.release()
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
        // Check if libVLC is initialized and discoverer exists and is not started
        if (libVLC != null) {
            if (rendererDiscoverer == null) { // If discoverer was nullified (e.g. after full stop)
                startDiscovery()
            } else if (rendererDiscoverer?.isStarted == false) { // Check property 'isStarted'
                rendererDiscoverer?.setEventListener(this@MainActivity) // Re-set listener
                if (rendererDiscoverer?.start() == false) {
                     Log.e(TAG, "Failed to restart renderer discovery in onResume.")
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            stopDiscovery()
            // Full cleanup happens in onDestroy
        } else {
            // Optionally stop discovery to save battery if app is paused for a long time
            // For now, let it run if not finishing.
            // rendererDiscoverer?.setEventListener(null) // To prevent callbacks when paused if not stopping
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery() // Ensure discovery is stopped and listener is removed
        rendererDiscoverer = null // Nullify to allow recreation

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
        // Custom setter to manage release of the old item is complex due to shared nature.
        // MainActivity should be responsible for releasing items it puts here when they are replaced or no longer needed.
}
