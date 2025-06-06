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
                RendererHolder.selectedRendererType = -1 // Reset to default
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
                    // It's important that item.name is not null for the 'any' and 'add' logic if we rely on it as a key.
                    // Assuming item.name is a reliable identifier from LibVLC, even if sometimes null.
                    // If item.name can be null and we still want to add, this logic might need adjustment
                    // or ensure 'name' in RendererItem is treated as nullable throughout.
                    // For now, proceeding with the assumption that a non-null name is typical for 'any' check.
                    if (item.name != null && !discoveredRenderers.any { it.name == item.name }) {
                        discoveredRenderers.add(item)
                    } else if (item.name == null) {
                        // Handle case where item.name is null - perhaps log or add differently if needed
                        Log.w(TAG, "Added a renderer with a null name. DisplayName: $itemDisplayName")
                        // If null named items should be added and are distinguishable by other means, adjust here.
                        // For now, we'll add it if its name is null, assuming it's a distinct (though unnamed) item.
                        // This might lead to multiple "Unknown Name" items if not careful.
                        // A more robust solution might involve checking other properties or instance equality.
                        discoveredRenderers.add(item) // Reconsidering: adding null-named items might be problematic for removal
                    }
                }
                updateRendererListUI()
            }
            RendererDiscoverer.Event.ItemDeleted -> {
                item ?: return
                val itemName = item.name ?: "Unknown Name" // For logging
                Log.d(TAG, "Renderer Removed: $itemName (Type: ${item.type})")

                synchronized(discoveredRenderers) {
                    if (item.name != null) {
                        discoveredRenderers.removeAll { it.name == item.name }
                    } else {
                        // If items with null names were added, how to remove them?
                        // This becomes tricky. For now, this will only remove items with matching non-null names.
                        // A more robust way would be to remove by object reference if possible, or use a unique ID.
                        Log.w(TAG, "Attempting to remove a renderer with a null name. This might not work as expected.")
                        // discoveredRenderers.remove(item) // This would require RendererItem to have a proper equals/hashCode
                    }
                }

                val currentSelectedItemName = selectedRenderer?.name
                val deletedItemName = item.name // Can be null

                // Only proceed if deletedItemName is not null, as selectedRenderer?.name could be null
                // and we need a valid name to compare against RendererHolder.selectedRendererName
                if (deletedItemName != null && currentSelectedItemName == deletedItemName) {
                    Log.d(TAG, "Selected renderer was removed: $deletedItemName")
                    val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                        action = ScreenCastingService.ACTION_STOP_CASTING
                    }
                    startService(serviceIntent)
                    selectedRenderer = null
                    // RendererHolder.selectedRendererName should also be a non-null name if set
                    if (RendererHolder.selectedRendererName == deletedItemName) {
                        RendererHolder.selectedRendererName = null
                        RendererHolder.selectedRendererType = -1 // Reset to default
                    }
                    binding.textViewStatus.text = getString(R.string.casting_stopped)
                } else if (deletedItemName == null && selectedRenderer != null && selectedRenderer?.name == null) {
                    // Special case: if the selected renderer had a null name and the deleted item also has a null name.
                    // This is heuristic and might not be perfectly accurate if there are multiple null-named renderers.
                    Log.d(TAG, "A selected renderer with a null name might have been removed.")
                    // Action similar to above, assuming this is the one.
                     val serviceIntent = Intent(this, ScreenCastingService::class.java).apply {
                        action = ScreenCastingService.ACTION_STOP_CASTING
                    }
                    startService(serviceIntent)
                    selectedRenderer = null
                    if (RendererHolder.selectedRendererName == null) { // If it was stored as null
                        RendererHolder.selectedRendererType = -1
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
        stopDiscovery() // This will set rendererDiscoverer to null
        // rendererDiscoverer = null // Redundant, already handled by stopDiscovery
        discoveredRenderers.clear()
        selectedRenderer = null
        RendererHolder.selectedRendererName = null
        RendererHolder.selectedRendererType = -1 // Reset to default
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
    var selectedRendererType: Int = -1 // Using Int, as RendererItem.type is Int
}
