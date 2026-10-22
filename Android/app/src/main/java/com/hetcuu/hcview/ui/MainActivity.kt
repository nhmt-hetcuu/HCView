package com.hetcuu.hcview.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.hetcuu.hcview.R
import com.hetcuu.hcview.databinding.ActivityMainBinding
import com.hetcuu.hcview.databinding.ItemIpAddressBinding
import com.hetcuu.hcview.model.AppState
import com.hetcuu.hcview.model.ConnectionMode
import com.hetcuu.hcview.model.ConnectionStatus
import com.hetcuu.hcview.model.RemoteConfig
import com.hetcuu.hcview.network.NetworkUtils
import com.hetcuu.hcview.network.RelayClient
import com.hetcuu.hcview.protocol.FramePacker
import com.hetcuu.hcview.service.CaptureService
import com.hetcuu.hcview.service.RemoteA11yService
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: RemoteConfig
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var isUpdatingA11yUI = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Cần cấp quyền thông báo để chạy tiến trình chia sẻ", Toast.LENGTH_SHORT).show()
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Chưa cấp quyền chụp màn hình", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calculateScreenSize()
        config = RemoteConfig.load(this, screenWidth, screenHeight)

        // Giữ màn hình luôn mở theo cài đặt người dùng
        if (config.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setupUI()
        setupListeners()
        observeAppState()
        checkPermissions()
        refreshIpAddresses()
    }

    override fun onResume() {
        super.onResume()
        updateA11yUI()
        refreshIpAddresses()
    }

    private fun calculateScreenSize() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun updateA11yUI(running: Boolean = RemoteA11yService.isRunning()) {
        isUpdatingA11yUI = true
        try {
            binding.switchA11y.isChecked = running
            if (running) {
                binding.ivA11yStatusIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.status_green)
                binding.tvA11ySubtitle.text = "Đang bật • Sẵn sàng nhận lệnh điều khiển từ xa"
                binding.tvA11ySubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            } else {
                binding.ivA11yStatusIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
                binding.tvA11ySubtitle.text = "Chưa bật • Chạm để kích hoạt trợ năng"
                binding.tvA11ySubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        } finally {
            isUpdatingA11yUI = false
        }
    }

    private fun handleA11yToggle(enable: Boolean) {
        if (!enable) {
            val stopped = RemoteA11yService.disableService(this)
            if (stopped) {
                Toast.makeText(this, "Đã tắt Dịch vụ Trợ năng ngay trong app", Toast.LENGTH_SHORT).show()
            } else {
                RemoteA11yService.openAccessibilitySettings(this)
            }
            updateA11yUI(false)
        } else {
            lifecycleScope.launch {
                val hasRoot = withContext(Dispatchers.IO) {
                    RemoteA11yService.enableWithRoot(this@MainActivity)
                }
                if (hasRoot) {
                    Toast.makeText(this@MainActivity, "Đã bật Trợ năng thành công qua Root!", Toast.LENGTH_SHORT).show()
                    kotlinx.coroutines.delay(400)
                    updateA11yUI()
                } else {
                    RemoteA11yService.openAccessibilitySettings(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        "Vui lòng gạt Bật 'HCView' trong Cài đặt Trợ năng",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupUI() {
        // Tab Chế độ A / B
        binding.tabLayoutMode.selectTab(binding.tabLayoutMode.getTabAt(
            if (config.mode == ConnectionMode.DIRECT_SERVER) 0 else 1
        ))
        updateModeVisibility(config.mode)

        // Cổng direct
        binding.etDirectPort.setText(config.directPort.toString())

        // Relay config
        binding.etRelayUrl.setText(config.relayServerUrl)
        binding.etRelayUsername.setText(config.relayUsername)
        binding.etRelayToken.setText(config.relayToken)
        binding.etRelayDeviceId.setText(config.relayDeviceId)


        // Thu phóng dải ngang & phóng to đơn vị (Zoom)
        val zoom = config.zoomLevel.coerceIn(1.0f, 4.0f)
        binding.sliderZoom.value = zoom
        binding.tvZoomLabel.text = "${"%.1f".format(zoom)}x"
        when (zoom) {
            1.0f -> binding.toggleZoom.check(R.id.btnZoom1x)
            1.5f -> binding.toggleZoom.check(R.id.btnZoom15x)
            2.0f -> binding.toggleZoom.check(R.id.btnZoom2x)
            3.0f -> binding.toggleZoom.check(R.id.btnZoom3x)
            else -> binding.toggleZoom.clearChecked()
        }

        // Tăng nét & Tương phản chữ
        when (config.contrastLevel) {
            2 -> binding.toggleContrast.check(R.id.btnContrastHigh)
            0 -> binding.toggleContrast.check(R.id.btnContrastOff)
            else -> binding.toggleContrast.check(R.id.btnContrastBalanced)
        }

        // Dithering
        if (config.ditherMode == 1) {
            binding.toggleDither.check(R.id.btnDitherSmooth)
        } else {
            binding.toggleDither.check(R.id.btnDitherSharp)
        }

        // Vùng chụp X & Y
        updateSlidersAndInfo()

        // Checkbox giữ màn hình luôn mở & Overlay
        binding.cbKeepScreenOn.isChecked = config.keepScreenOn
        binding.cbShowOverlay.isChecked = config.showOverlay

        // Invert
        binding.cbInvert.isChecked = config.invert

        // FPS
        binding.sliderFps.value = config.fps.toFloat()
        binding.tvFpsLabel.text = "${config.fps} fps"

        // Pan Ratio
        binding.sliderPanRatio.value = config.panRatio
        binding.tvPanRatioLabel.text = "Bước nhảy dời vùng khi bấm phím mũi tên (M): ${(config.panRatio * 100).toInt()}%"

        // Tốc độ cuộn trang (Scroll Speed)
        binding.sliderScrollSpeed.value = config.scrollSpeed.coerceIn(0.2f, 3.0f)
        binding.tvScrollSpeedLabel.text = "${"%.1f".format(config.scrollSpeed)}x"
    }

    private fun updateModeVisibility(mode: ConnectionMode) {
        if (mode == ConnectionMode.DIRECT_SERVER) {
            binding.layoutDirectMode.visibility = View.VISIBLE
            binding.layoutRelayMode.visibility = View.GONE
        } else {
            binding.layoutDirectMode.visibility = View.GONE
            binding.layoutRelayMode.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.tabLayoutMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                config.mode = if (tab?.position == 0) ConnectionMode.DIRECT_SERVER else ConnectionMode.RELAY_SERVER
                updateModeVisibility(config.mode)
                refreshIpAddresses()
                saveConfig()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnToggleSharing.setOnClickListener {
            if (CaptureService.isRunning) {
                stopCaptureService()
            } else {
                saveConfig()
                requestScreenCapture()
            }
        }


        // Bộ chọn Thu phóng nhanh (Presets)
        binding.toggleZoom.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val zoom = when (checkedId) {
                    R.id.btnZoom15x -> 1.5f
                    R.id.btnZoom2x -> 2.0f
                    R.id.btnZoom3x -> 3.0f
                    else -> 1.0f
                }
                if (Math.abs(config.zoomLevel - zoom) > 0.01f) {
                    binding.sliderZoom.value = zoom
                    applyZoom(zoom)
                }
            }
        }

        // Thanh trượt Zoom liên tục (1.0x - 4.0x) phóng to đơn vị & chữ
        binding.sliderZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                when (value) {
                    1.0f -> binding.toggleZoom.check(R.id.btnZoom1x)
                    1.5f -> binding.toggleZoom.check(R.id.btnZoom15x)
                    2.0f -> binding.toggleZoom.check(R.id.btnZoom2x)
                    3.0f -> binding.toggleZoom.check(R.id.btnZoom3x)
                    else -> binding.toggleZoom.clearChecked()
                }
                applyZoom(value)
            }
        }

        // Bộ chọn Tăng nét & Tương phản chữ
        binding.toggleContrast.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                config.contrastLevel = when (checkedId) {
                    R.id.btnContrastHigh -> 2
                    R.id.btnContrastBalanced -> 1
                    else -> 0
                }
                saveConfig()
                AppState.requestImmediateFrame()
            }
        }

        // Bộ chọn Dither
        binding.toggleDither.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                config.ditherMode = if (checkedId == R.id.btnDitherSmooth) 1 else 0
                saveConfig()
                AppState.requestImmediateFrame()
            }
        }

        // Bật/tắt giữ màn hình luôn mở
        binding.cbKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            config.keepScreenOn = isChecked
            if (isChecked) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Toast.makeText(this, "Đã bật: Màn hình luôn mở khi hoạt động", Toast.LENGTH_SHORT).show()
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            saveConfig()
        }

        // Presets vị trí dọc (Y)
        binding.btnPresetTop.setOnClickListener {
            setRegionPos(x = config.region.left, y = 100)
        }
        binding.btnPresetMiddle.setOnClickListener {
            val r = config.region
            setRegionPos(x = r.left, y = (screenHeight - r.height()) / 2)
        }
        binding.btnPresetBottom.setOnClickListener {
            val r = config.region
            setRegionPos(x = r.left, y = screenHeight - r.height() - 150)
        }

        // Căn lề ngang khi phóng to (X)
        binding.btnAlignLeft.setOnClickListener {
            setRegionPos(x = 0, y = config.region.top)
        }
        binding.btnAlignCenter.setOnClickListener {
            val r = config.region
            setRegionPos(x = (screenWidth - r.width()) / 2, y = r.top)
        }
        binding.btnAlignRight.setOnClickListener {
            val r = config.region
            setRegionPos(x = screenWidth - r.width(), y = r.top)
        }

        binding.sliderRegionX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                setRegionPos(x = value.toInt(), y = config.region.top)
            }
        }

        binding.sliderRegionY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                setRegionPos(x = config.region.left, y = value.toInt())
            }
        }

        binding.cbShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            config.showOverlay = isChecked
            saveConfig()
        }

        binding.sliderScrollSpeed.addOnChangeListener { _, value, _ ->
            config.scrollSpeed = value
            binding.tvScrollSpeedLabel.text = "${"%.1f".format(value)}x"
            saveConfig()
        }

        binding.cbInvert.setOnCheckedChangeListener { _, isChecked ->
            config.invert = isChecked
            saveConfig()
            AppState.requestImmediateFrame()
        }

        binding.sliderFps.addOnChangeListener { _, value, _ ->
            config.fps = value.toInt()
            binding.tvFpsLabel.text = "${config.fps} fps"
            saveConfig()
        }

        binding.sliderPanRatio.addOnChangeListener { _, value, _ ->
            config.panRatio = value
            binding.tvPanRatioLabel.text = "Bước nhảy dời vùng khi bấm phím mũi tên (M): ${(value * 100).toInt()}%"
            saveConfig()
        }

        binding.btnRelayLogin.setOnClickListener {
            performRelayLogin()
        }

        binding.btnRefreshIp.setOnClickListener {
            refreshIpAddresses()
            Toast.makeText(this, "Đã làm mới danh sách IP", Toast.LENGTH_SHORT).show()
        }

        binding.etDirectPort.doAfterTextChanged { text ->
            val port = text?.toString()?.toIntOrNull()
            if (port != null && port in 1024..65535) {
                config.directPort = port
                refreshIpAddresses()
                saveConfig()
            }
        }

        binding.layoutConnectedClient.setOnClickListener {
            val clients = AppState.connectedClients.value
            if (clients.isNotEmpty()) {
                val ipText = clients.joinToString(", ")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Client IP", ipText))
                Toast.makeText(this, "Đã sao chép IP thiết bị: $ipText", Toast.LENGTH_SHORT).show()
            }
        }

        // Cài đặt Dịch vụ Trợ năng: Bật / Tắt 1 chạm gọn gàng
        binding.switchA11y.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingA11yUI) {
                handleA11yToggle(isChecked)
            }
        }

        binding.cardA11ySettings.setOnClickListener {
            binding.switchA11y.toggle()
        }
    }


    private fun applyRegionMode() {
        val zoom = config.zoomLevel.coerceIn(1.0f, 4.0f)
        val rw = (screenWidth / zoom).toInt().coerceIn(RemoteConfig.LCD_W, screenWidth)
        val rh = (rw * RemoteConfig.LCD_H / RemoteConfig.LCD_W).coerceAtLeast(RemoteConfig.LCD_H)

        val safeX = config.region.left.coerceIn(0, (screenWidth - rw).coerceAtLeast(0))
        val safeY = config.region.top.coerceIn(0, (screenHeight - rh).coerceAtLeast(0))

        config.region = Rect(safeX, safeY, safeX + rw, safeY + rh)
        AppState.updateActiveRegion(config.region)
        updateSlidersAndInfo()
        saveConfig()
        AppState.requestImmediateFrame()
    }

    private fun applyZoom(zoom: Float) {
        config.zoomLevel = zoom
        applyRegionMode()
    }

    private fun setRegionPos(x: Int, y: Int) {
        val rw = config.region.width()
        val rh = config.region.height()
        val safeX = x.coerceIn(0, (screenWidth - rw).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (screenHeight - rh).coerceAtLeast(0))

        config.region = Rect(safeX, safeY, safeX + rw, safeY + rh)
        AppState.updateActiveRegion(config.region)
        updateSlidersAndInfo()
        saveConfig()
        AppState.requestImmediateFrame()
    }

    private fun updateSlidersAndInfo() {
        val r = config.region
        val maxTargetX = (screenWidth - r.width()).toFloat().coerceAtLeast(0f)
        val maxTargetY = (screenHeight - r.height()).toFloat().coerceAtLeast(0f)

        binding.sliderRegionX.valueTo = if (maxTargetX > 0) maxTargetX else 1f
        binding.sliderRegionX.value = r.left.toFloat().coerceIn(0f, binding.sliderRegionX.valueTo)

        binding.sliderRegionY.valueTo = if (maxTargetY > 0) maxTargetY else 1f
        binding.sliderRegionY.value = r.top.toFloat().coerceIn(0f, binding.sliderRegionY.valueTo)

        // Căn lề ngang chỉ cần thiết khi dải ngang hẹp hơn toàn màn hình
        binding.layoutHorizontalAlign.visibility = if (maxTargetX > 0) View.VISIBLE else View.GONE

        binding.tvZoomLabel.text = "${"%.1f".format(config.zoomLevel)}x"
        binding.tvRegionInfo.text = "Dải ngang: (${r.left}, ${r.top}) | Kích thước: ${r.width()} × ${r.height()} px | Phóng to: ${"%.1f".format(config.zoomLevel)}x"

        val currentZoom = config.zoomLevel.coerceIn(1.0f, 4.0f)
        if (Math.abs(binding.sliderZoom.value - currentZoom) >= 0.05f) {
            binding.sliderZoom.value = currentZoom
        }
        when (currentZoom) {
            1.0f -> binding.toggleZoom.check(R.id.btnZoom1x)
            1.5f -> binding.toggleZoom.check(R.id.btnZoom15x)
            2.0f -> binding.toggleZoom.check(R.id.btnZoom2x)
            3.0f -> binding.toggleZoom.check(R.id.btnZoom3x)
            else -> binding.toggleZoom.clearChecked()
        }
    }

    private fun refreshIpAddresses() {
        binding.layoutIpContainer.removeAllViews()
        val port = binding.etDirectPort.text.toString().toIntOrNull() ?: 8268
        val ipList = NetworkUtils.getLocalIpv4Addresses()

        if (ipList.isEmpty()) {
            binding.tvPrimaryIp.text = "Chưa kết nối WiFi hoặc Hotspot"
            binding.tvPrimaryIp.setTextColor(ContextCompat.getColor(this, R.color.status_orange))
            val tv = TextView(this).apply {
                text = "⚠️ Chưa kết nối WiFi hoặc Hotspot. Hãy bật WiFi hoặc phát Hotspot cho thiết bị."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_orange))
                setPadding(12, 12, 12, 12)
            }
            binding.layoutIpContainer.addView(tv)
            return
        }

        // Hiện IP chính ngay trên thẻ hero IP
        val primaryAddress = "${ipList[0].ipAddress}:$port"
        binding.tvPrimaryIp.text = primaryAddress
        binding.tvPrimaryIp.setTextColor(ContextCompat.getColor(this, R.color.primary))
        binding.layoutHeroIp.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HCView IP", primaryAddress))
            Toast.makeText(this, "Đã sao chép: $primaryAddress", Toast.LENGTH_SHORT).show()
        }

        val inflater = LayoutInflater.from(this)
        for (item in ipList) {
            val itemBinding = ItemIpAddressBinding.inflate(inflater, binding.layoutIpContainer, false)
            val fullAddress = "${item.ipAddress}:$port"
            itemBinding.tvIpAddress.text = fullAddress
            itemBinding.tvInterfaceName.text = if (item.isHotspot) {
                "🔥 Điểm phát sóng di động (Hotspot - ${item.interfaceName})"
            } else {
                "📶 Mạng WiFi (${item.interfaceName})"
            }

            itemBinding.btnCopyIp.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("HCView IP", fullAddress))
                Toast.makeText(this, "Đã chép: $fullAddress", Toast.LENGTH_SHORT).show()
            }

            binding.layoutIpContainer.addView(itemBinding.root)
        }
    }

    private fun observeAppState() {
        lifecycleScope.launch {
            AppState.connectionStatus.collectLatest { status ->
                updateConnectionStatusUI(status)
            }
        }

        lifecycleScope.launch {
            AppState.statusMessage.collectLatest { msg ->
                binding.tvStatusTitle.text = msg
            }
        }

        lifecycleScope.launch {
            AppState.clientCount.collectLatest { count ->
                val clients = AppState.connectedClients.value
                binding.tvClientCount.text = if (clients.size == 1) clients[0] else "$count thiết bị"
            }
        }

        lifecycleScope.launch {
            AppState.connectedClients.collectLatest { clients ->
                if (clients.isNotEmpty()) {
                    val clientIpText = clients.joinToString(", ")
                    binding.tvConnectedClientIp.text = clientIpText
                    binding.tvConnectedClientIp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    binding.tvConnectedClientBadge.text = "ONLINE"
                    binding.tvConnectedClientBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    binding.tvConnectedClientBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_green_bg)
                    binding.ivConnectedClientIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_green)

                    binding.tvClientCount.text = if (clients.size == 1) clients[0] else "${clients.size} thiết bị"
                } else {
                    binding.tvConnectedClientIp.text = if (AppState.connectionStatus.value == ConnectionStatus.STOPPED) {
                        "Đã dừng chia sẻ"
                    } else {
                        "Chờ thiết bị kết nối..."
                    }
                    binding.tvConnectedClientIp.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    binding.tvConnectedClientBadge.text = "OFFLINE"
                    binding.tvConnectedClientBadge.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    binding.tvConnectedClientBadge.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.surface_variant)
                    binding.ivConnectedClientIcon.imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.text_muted)

                    binding.tvClientCount.text = "0 thiết bị"
                }
            }
        }

        lifecycleScope.launch {
            AppState.actualFps.collectLatest { fps ->
                binding.tvStatFps.text = "FPS: %.1f".format(fps)
            }
        }

        lifecycleScope.launch {
            AppState.framesSent.collectLatest { frames ->
                binding.tvStatFrames.text = "$frames"
            }
        }

        lifecycleScope.launch {
            AppState.bytesSent.collectLatest { bytes ->
                val kb = bytes / 1024
                binding.tvStatBytes.text = if (kb >= 1024) "%.1f MB".format(kb / 1024f) else "$kb KB"
            }
        }


        lifecycleScope.launch {
            AppState.activeRegion.collectLatest { region ->
                config.region = region
                updateSlidersAndInfo()
            }
        }

        lifecycleScope.launch {
            AppState.scrollSpeed.collectLatest { speed ->
                config.scrollSpeed = speed
                if (Math.abs(binding.sliderScrollSpeed.value - speed) >= 0.05f) {
                    binding.sliderScrollSpeed.value = speed.coerceIn(0.2f, 3.0f)
                }
                binding.tvScrollSpeedLabel.text = "${"%.1f".format(speed)}x"
                saveConfig()
            }
        }

        lifecycleScope.launch {
            AppState.isA11yRunning.collectLatest { running ->
                updateA11yUI(running)
            }
        }
    }

    private fun updateConnectionStatusUI(status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.STOPPED -> {
                binding.btnToggleSharing.text = getString(R.string.btn_start)
                binding.btnToggleSharing.setBackgroundResource(R.drawable.bg_hero_button)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
            }
            ConnectionStatus.STARTING, ConnectionStatus.CONNECTING -> {
                binding.btnToggleSharing.text = getString(R.string.btn_stop)
                binding.btnToggleSharing.setBackgroundResource(R.drawable.bg_hero_button_stop)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_orange)
            }
            ConnectionStatus.LISTENING, ConnectionStatus.CONNECTED -> {
                binding.btnToggleSharing.text = getString(R.string.btn_stop)
                binding.btnToggleSharing.setBackgroundResource(R.drawable.bg_hero_button_stop)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_blue)
            }
            ConnectionStatus.PAIRED -> {
                binding.btnToggleSharing.text = getString(R.string.btn_stop)
                binding.btnToggleSharing.setBackgroundResource(R.drawable.bg_hero_button_stop)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_green)
            }
            ConnectionStatus.ERROR -> {
                binding.btnToggleSharing.text = getString(R.string.btn_start)
                binding.btnToggleSharing.setBackgroundResource(R.drawable.bg_hero_button)
                binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_red)
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: Bắt buộc chọn "Toàn bộ màn hình" để khớp hệ toạ độ chạm của A11y
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopCaptureService() {
        val serviceIntent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun performRelayLogin() {
        val url = binding.etRelayUrl.text.toString().trim()
        val user = binding.etRelayUsername.text.toString().trim()
        val pass = binding.etRelayPassword.text.toString().trim()

        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Server URL, Tài khoản và Mật khẩu", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnRelayLogin.isEnabled = false
        binding.btnRelayLogin.text = "Đang đăng nhập…"

        lifecycleScope.launch {
            val loginResult = RelayClient.login(url, user, pass)
            binding.btnRelayLogin.isEnabled = true
            binding.btnRelayLogin.text = "Đăng nhập & Lấy danh sách máy"

            loginResult.fold(
                onSuccess = { token ->
                    config.relayToken = token
                    binding.etRelayToken.setText(token)
                    Toast.makeText(this@MainActivity, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()

                    // Tiếp tục lấy danh sách thiết bị
                    val devResult = RelayClient.fetchDevices(url, token)
                    devResult.fold(
                        onSuccess = { devices ->
                            if (devices.isNotEmpty()) {
                                config.relayDeviceId = devices[0].id
                                binding.etRelayDeviceId.setText(devices[0].id)
                                Toast.makeText(this@MainActivity, "Đã chọn máy: ${devices[0].name} (${devices[0].id})", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Tài khoản chưa có thiết bị nào được liên kết", Toast.LENGTH_LONG).show()
                            }
                            saveConfig()
                        },
                        onFailure = {
                            Toast.makeText(this@MainActivity, "Lỗi lấy danh sách máy: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                onFailure = {
                    Toast.makeText(this@MainActivity, "Đăng nhập thất bại: ${it.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun saveConfig() {
        config.directPort = binding.etDirectPort.text.toString().toIntOrNull() ?: 8268
        config.relayServerUrl = binding.etRelayUrl.text.toString().trim()
        config.relayUsername = binding.etRelayUsername.text.toString().trim()
        config.relayToken = binding.etRelayToken.text.toString().trim()
        config.relayDeviceId = binding.etRelayDeviceId.text.toString().trim()
        config.save(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveConfig()
    }
}
