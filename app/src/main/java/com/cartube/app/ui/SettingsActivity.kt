package com.cartube.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.cartube.app.BuildConfig
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.ClientSourceHelper
import com.cartube.app.databinding.ActivitySettingsBinding
import com.cartube.app.playback.CarPlaybackService

@UnstableApi
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: CarPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = CarPrefs(this)

        binding.btnBack.setOnClickListener { finish() }

        when (prefs.maxVideoHeight) {
            480 -> binding.rb480p.isChecked = true
            720 -> binding.rb720p.isChecked = true
            1080 -> binding.rb1080p.isChecked = true
            else -> binding.rbAuto.isChecked = true
        }

        binding.rgQuality.setOnCheckedChangeListener { _, checkedId ->
            val height = when (checkedId) {
                binding.rb480p.id -> 480
                binding.rb720p.id -> 720
                binding.rb1080p.id -> 1080
                else -> 0
            }
            prefs.maxVideoHeight = height
            CarPlaybackService.instance?.updateMaxVideoHeight(height)
        }

        binding.swBlockAv1.isChecked = prefs.blockAv1
        binding.swBlockAv1.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockAv1 = isChecked
            CarPlaybackService.instance?.refreshTrackPreferences()
        }

        binding.swBgAudioOnly.isChecked = prefs.backgroundAudioOnly
        binding.swBgAudioOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.backgroundAudioOnly = isChecked
        }

        binding.swSponsorBlock.isChecked = prefs.sponsorBlockEnabled
        binding.swSponsorBlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.sponsorBlockEnabled = isChecked
        }

        updateRememberedSourceLabel()
        binding.btnResetSource.setOnClickListener {
            prefs.preferredClientName = null
            ClientSourceHelper.resetClient()
            updateRememberedSourceLabel()
            Toast.makeText(this, "Đã đặt lại nguồn phát (sẽ tự dò lại ở video tiếp theo)", Toast.LENGTH_SHORT).show()
        }

        binding.tvAppInfo.text =
            "CarTube v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})\n" +
                "• Chặn quảng cáo YouTube gốc: Luôn bật (InnerTube API)\n" +
                "• Trình phát: AndroidX Media3 + Cronet HTTP/3 QUIC\n" +
                "• Điều khiển vô-lăng: Android MediaSession + AudioFocus"
    }

    private fun updateRememberedSourceLabel() {
        val saved = prefs.preferredClientName
        binding.tvRememberedSource.text = if (saved.isNullOrBlank()) {
            "Nguồn phát đang nhớ: Tự động (chưa lưu)"
        } else {
            "Nguồn phát đang nhớ: $saved"
        }
    }
}
