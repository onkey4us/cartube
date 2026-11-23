# CarTube

Ứng dụng YouTube **Native siêu nhẹ** tối ưu riêng cho đầu màn hình Android trên ô tô (ví dụ: Beijing X7 `C3005H_MX8Q`, Android 9+, `arm64-v8a` / `armeabi-v7a`).

## Tính năng chính
- **Chặn quảng cáo gốc 100%** ngay tầng YouTube InnerTube API (sử dụng lõi [`yuliskov/MediaServiceCore`](https://github.com/yuliskov/MediaServiceCore)).
- **SponsorBlock tự động:** bỏ qua đoạn tài trợ, tự quảng cáo, intro/outro trong video.
- **Đăng nhập tài khoản Google bằng QR (OAuth2 TV Flow):** quét mã QR bằng điện thoại để đồng bộ Trang chủ, Kênh đăng ký, Lịch sử xem — không cần nhập mật khẩu trên xe, không cần MicroG/GMS.
- **Siêu nhẹ cho phần cứng ô tô:**
  - Giao diện Native Kotlin + `RecyclerView`, không nhúng Chromium/WebView.
  - Phát video bằng **AndroidX Media3 (`ExoPlayer`) + `SurfaceView`** giải mã phần cứng.
  - Mặc định chặn AV1 software decode (ưu tiên H.264/VP9 phần cứng cho chip i.MX8Q).
  - **Chế độ âm thanh nền tự động:** khi bạn thu nhỏ sang Vietmap Live / Google Maps, app tự tắt video track và chỉ giữ tiếng để hạ CPU tối đa.
- **Điều khiển vô-lăng & cảm ứng ô tô:**
  - Tích hợp chuẩn `MediaSession` + `AudioFocus` (hỗ trợ Next/Prev/Play/Pause/Seek trên vô-lăng, hiển thị tiêu đề lên widget xe, tự xử lý âm lượng khi có giọng dẫn đường/lùi xe).
  - Nút cảm ứng lớn (56dp–86dp), chạm đúp trái/phải tua ±10 giây, thanh Mini-Player và danh sách "Tiếp theo".

## Build APK tự động bằng GitHub Actions (Không cần cài Android SDK trên máy)
1. Đẩy thư mục `CarTube` lên một repository GitHub (public hoặc private).
2. Vào tab **Actions** → chạy workflow **Build CarTube APK** (hoặc tự chạy mỗi khi `git push` lên `main`/`master`).
3. Sau khi build xong (~3–5 phút), tải artifact **CarTube-APKs**:
   - `app-arm64-v8a-release.apk` → **Khuyên dùng cho đầu Beijing X7 (`C3005H_MX8Q`)** (nhẹ nhất).
   - `app-armeabi-v7a-release.apk` → Dành cho đầu Android 32-bit đời cũ.
   - `app-universal-release.apk` → Dùng chung mọi máy ARM.
4. Cài lên đầu xe qua ADB:
   ```bash
   adb install -r app-arm64-v8a-release.apk
   ```

## Build cục bộ (Android Studio / Gradle)
Yêu cầu **JDK 17** và **Android SDK (API 34 + Build Tools 30.0.3)**:
```bash
git clone --recursive --depth 1 https://github.com/yuliskov/MediaServiceCore.git MediaServiceCore
cp -R MediaServiceCore/SharedModules SharedModules
./gradlew :app:assembleRelease
```
