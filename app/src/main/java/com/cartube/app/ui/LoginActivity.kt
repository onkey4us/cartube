package com.cartube.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cartube.app.data.SignInState
import com.cartube.app.data.YouTubeRepository
import com.cartube.app.databinding.ActivityLoginBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var signInJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSignOut.setOnClickListener {
            YouTubeRepository.signOutAll()
            Toast.makeText(this, "Đã đăng xuất tài khoản Google", Toast.LENGTH_SHORT).show()
            renderState()
        }
        binding.btnStartLogin.setOnClickListener {
            startQrSignIn()
        }

        renderState()
    }

    override fun onDestroy() {
        signInJob?.cancel()
        super.onDestroy()
    }

    private fun renderState() {
        val account = YouTubeRepository.getSelectedAccount()
        if (account != null) {
            binding.signedInContainer.visibility = View.VISIBLE
            binding.qrContainer.visibility = View.GONE
            binding.tvAccountName.text = account.name ?: "Tài khoản Google"
            binding.tvAccountEmail.text = account.email ?: "Đã đăng nhập qua OAuth2"
        } else {
            binding.signedInContainer.visibility = View.GONE
            binding.qrContainer.visibility = View.VISIBLE
            startQrSignIn()
        }
    }

    private fun startQrSignIn() {
        signInJob?.cancel()
        binding.progressQr.visibility = View.VISIBLE
        binding.ivQrCode.visibility = View.INVISIBLE
        binding.tvActivationCode.text = "---- ----"
        binding.tvLoginStatus.text = "Đang lấy mã kích hoạt từ YouTube..."

        signInJob = lifecycleScope.launch {
            YouTubeRepository.signInFlow().collectLatest { state ->
                when (state) {
                    is SignInState.Loading -> {
                        binding.progressQr.visibility = View.VISIBLE
                    }
                    is SignInState.CodeReady -> {
                        binding.progressQr.visibility = View.GONE
                        binding.ivQrCode.visibility = View.VISIBLE
                        binding.tvActivationCode.text = state.code
                        binding.tvActivationUrl.text = state.verifyUrl
                        binding.tvLoginStatus.text =
                            "Quét mã QR bằng điện thoại hoặc truy cập ${state.verifyUrl} rồi nhập mã trên"
                        binding.ivQrCode.setImageBitmap(generateQrBitmap(state.verifyUrl, 420))
                    }
                    is SignInState.Success -> {
                        Toast.makeText(
                            this@LoginActivity,
                            "Đăng nhập thành công: ${state.account?.name ?: "YouTube"}",
                            Toast.LENGTH_LONG
                        ).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    is SignInState.Error -> {
                        binding.progressQr.visibility = View.GONE
                        binding.tvLoginStatus.text = "Lỗi: ${state.message}"
                    }
                }
            }
        }
    }

    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }
}
