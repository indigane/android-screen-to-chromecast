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
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "MediaProjection permission denied.")
                binding.textViewStatus.text = getString(R.string.error_prefix) + "Screen capture permission denied."
                RendererHolder.selectedRendererName = null
                RendererHolder.selectedRendererType = null
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
                RendererHolder.selectedRendererName = clickedRenderer.name
                // Assuming clickedRenderer.type is String as per subtask instruction
                RendererHolder.selectedRendererType = clickedRenderer.type

                // Use direct field access as confirmed by Javadoc for 3.6.1
                val clickedRendererName = clickedRenderer.name ?: "Unknown Name"
                val clickedRendererDisplayName = clickedRenderer.displayName ?: clickedRendererName
                Log.d(TAG, "Selected renderer: $clickedRendererName (Type: ${clickedRenderer.type}, DisplayName: $clickedRendererDisplayName)")
                binding.textViewStatus.text = getString(R.string.casting_to, clickedRendererDisplayName)

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
        rendererDiscoverer?.let { discoverer ->
            discoverer.setEventListener(null)
            discoverer.stop()
            rendererDiscoverer = null
            Log.d(TAG, "Renderer discovery stopped and instance nullified.")
        }
    }

    override fun onEvent(event: RendererDiscoverer.Event) {
        if (libVLC == null || rendererDiscoverer == null) {
            Log.w(TAG, "onEvent received but LibVLC or RendererDiscoverer is not active. Ignoring event.")
            return
        }
        val item = event.item

        when (event.type) {
            RendererDiscoverer.Event.ItemAdded -> {
                item ?: return
                val itemName = item.name ?: "Unknown Name"
                val itemDisplayName = item.displayName ?: "N/A"
                Log.d(TAG, "Renderer Added: $itemName (Type: ${item.type}, DisplayName: $itemDisplayName)")
                synchronized(discoveredRenderers) {
                    var alreadyExists = false
                    // Check if a renderer with the same name and type already exists to prevent duplicates.
                    // This is a common pattern for LibVLC renderer discovery.
                    // Also handle null names by reference equality if possible, or by displayName if names are null.
                    if (item.name != null) {
                        alreadyExists = discoveredRenderers.any { it.name == item.name && it.type == item.type }
                    } else { // item.name is null, try to match by reference or a display name if that's all we have
                        alreadyExists = discoveredRenderers.any { it == item || (it.name == null && it.displayName == item.displayName) }
                    }

                    if (!alreadyExists) {
                        item.retain() // Retain the item as we are holding a reference
                        discoveredRenderers.add(item)
                        Log.i(TAG, "Added and retained renderer: ${item.displayName ?: itemName} (Type: ${item.type})")
                    } else {
                        Log.d(TAG, "Renderer '${item.displayName ?: itemName}' (Type: ${item.type}) already in list or name collision. Event item not added or retained by list.")
                        // If it's already in the list, the incoming 'item' from the event is a new instance
                        // representing the same renderer by value, so it should be released as we won't use this instance.
                        item.release()
                        Log.d(TAG, "Released duplicate event item: ${item.displayName ?: itemName}")
                    }
                }
                updateRendererListUI()
            }
            RendererDiscoverer.Event.ItemDeleted -> {
                item ?: return
                val itemName = item.name ?: "Unknown Name" // For logging
                Log.d(TAG, "Renderer Removed: $itemName (Type: ${item.type})")

                synchronized(discoveredRenderers) {
                    val iterator = discoveredRenderers.iterator()
                    var releasedFromList = false
                    while (iterator.hasNext()) {
                        val existingItem = iterator.next()
                        // Robust matching: by reference or by name & type
                        if (existingItem == item || (existingItem.name == item.name && existingItem.type == item.type)) {
                            iterator.remove()
                            existingItem.release()
                            releasedFromList = true
                            Log.i(TAG, "Removed and released renderer from list: ${existingItem.displayName ?: existingItem.name}")
                            break
                        }
                    }
                    // If the item from the event was not found in our list (e.g. already removed, or never matched for add)
                    // then we should release this event item itself as we are not managing it.
                    if (!releasedFromList) {
                        item.release()
                        Log.d(TAG, "Released event item (not found in list for removal): ${item.displayName ?: itemName}")
                    }
                }

                // If the selected renderer is the one being deleted
                if (selectedRenderer == item || (selectedRenderer?.name == item.name && selectedRenderer?.type == item.type)) {
                    Log.i(TAG, "Selected renderer ('${selectedRenderer?.displayName ?: selectedRenderer?.name}') was removed.")
                    val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                        action = ScreenCastingService.ACTION_STOP_CASTING
                    }
                    startService(serviceIntent)
                    // selectedRenderer?.release() // The instance pointed to by selectedRenderer was in discoveredRenderers, so it's released above.
                    selectedRenderer = null
                    RendererHolder.selectedRendererName = null
                    RendererHolder.selectedRendererType = null
                    binding.textViewStatus.text = getString(R.string.casting_stopped)
                }
                updateRendererListUI()
            }
            else -> {
                // For other events, if event.item is not null, it's good practice to release it if not used.
                event.item?.release()
            }
        }
    }

    private fun updateRendererListUI() {
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
        if (libVLC != null) { // Ensure LibVLC is ready
            startDiscovery() // This will handle creation if null and starting
        }
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()

        Log.d(TAG, "MainActivity onDestroy: Releasing renderers.")
        synchronized(discoveredRenderers) {
            discoveredRenderers.forEach { renderer ->
                renderer.release()
                Log.d(TAG, "Released discovered renderer: ${renderer.displayName ?: renderer.name}")
            }
            discoveredRenderers.clear()
        }
        selectedRenderer?.release() // Release the selected one if it's still held
        Log.d(TAG, "Released selectedRenderer: ${selectedRenderer?.displayName ?: selectedRenderer?.name}")
        selectedRenderer = null

        RendererHolder.selectedRendererName = null
        RendererHolder.selectedRendererType = null
        libVLC?.release()
        libVLC = null
        Log.d(TAG, "MainActivity onDestroy: LibVLC released.")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

object RendererHolder {
    var selectedRendererName: String? = null
    var selectedRendererType: String? = null
}
