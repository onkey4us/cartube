# Hồ sơ thiết bị: đầu xe Beijing X7 (C3005H_MX8Q)

Thông số đo trực tiếp trên xe qua adb ngày **2026-11-25**, dùng làm căn cứ khi tối ưu CarTube.
Mọi con số ở đây là số đọc được từ máy, trừ những chỗ ghi rõ "suy ra". Cách đo lại ở cuối file.

## Tóm tắt cho người viết code

| Ràng buộc | Giá trị | Hệ quả cho app |
|---|---|---|
| Giải mã cứng | H.264, HEVC (đến 4096x2160); VP8, MPEG-2/4, H.263 (đến 1080p) | Luôn ưu tiên `avc1`. YouTube hầu như không phát HEVC. |
| VP9 | **Chỉ phần mềm** (`OMX.google.vp9.decoder`): 36 fps ở 720p, 21 fps ở 1080p | VP9 720p30 vừa đủ; VP9 720p60 và 1080p sẽ rớt khung hình. |
| AV1 | Không có bộ giải mã nào | Giữ `blockAv1 = true`. |
| Decoder cứng không có `adaptive-playback` | Cả `avc` và `hevc` của Freescale | Đổi độ phân giải giữa bài phải khởi tạo lại codec, có thể khựng nhẹ. Hạn chế nhảy chất lượng. |
| Màn hình chính | 1920x720, **50 Hz**, 160 dpi | Video 30/60 fps không chia hết cho 50 Hz nên luôn hơi giật nhịp (judder), không phải lỗi mạng hay giải mã. 720p là đủ nét. |
| Heap Java mỗi app | `heapgrowthlimit` 192 MB (app không bật `largeHeap`) | Buffer ExoPlayer nằm trên heap Java: giữ trần 80 MB như hiện tại, không nâng quá ~100 MB. |
| CPU Android thấy | 5 nhân: 1x Cortex-A72 1,6 GHz + 4x Cortex-A53 1,2 GHz | Việc nặng một luồng chỉ có một nhân nhanh. Tránh làm nặng main thread. |
| Tải nền | Load average 7-10 khi chạy cùng Google Maps + launcher + navi | CPU đã bận sẵn khoảng 50%; phần dư cho giải mã phần mềm rất ít. |
| Mạng | Wi-Fi 2,4 GHz tới hotspot điện thoại (mạng mặc định) | Mất 4G thì Wi-Fi vẫn báo "connected": phải thử kết nối thật, không tin trạng thái mạng của Android. |
| PiP | Hệ thống **không** khai báo `android.software.picture_in_picture` | Code PiP trong `PlayerActivity` không dùng được trên máy này; đa cửa sổ đi qua `ActivityView` của launcher. |

## Hệ thống

- Bo mạch / model: `C3005H-MX8Q`, device `c3005h`, hãng khai báo `freescale`, platform `imx8`
- Device tree: `Freescale i.MX8QM C3005H`, SoC `i.MX8QM` revision 1.1
- Android 9 (SDK 28), bản vá bảo mật 2018-11-05, build `1.0.0-ga-rc3 dev-keys`, kiểu `user`
- Kernel 4.14.98 (build 2022-08-24), aarch64
- ABI: `arm64-v8a`, `armeabi-v7a`, `armeabi`
- Ngôn ngữ `vi-VN`, múi giờ `Asia/Ho_Chi_Minh`
- Phần mềm xe: launcher `com.qinggan.app.launcher`, dẫn đường `com.pateonavi.naviapp` (Pateo / Qinggan)
- adb qua Wi-Fi chạy quyền **root** và nghe ở cổng **5578** (không phải 5555)

## CPU

- 4 nhân Cortex-A53 (CPU part `0xd03`), tối đa 1,2 GHz
- 1 nhân Cortex-A72 (CPU part `0xd08`), tối đa 1,596 GHz
- Kernel chỉ thấy `cpu0-4`. i.MX8QM có 2 nhân A72; nhân còn lại không lộ ra cho Android
  (suy ra: được tách riêng cho hệ thống khác trên xe, chưa xác minh là hệ thống nào)
- Governor: `performance` trên cả 5 nhân, tức luôn chạy tần số tối đa

## Bộ nhớ và lưu trữ

- RAM: 5,71 GB tổng; lúc đo còn khả dụng khoảng 3,0 GB
- zram swap 300 MB, chưa dùng
- Dalvik: `heapstartsize` 16 MB, `heapgrowthlimit` 192 MB, `heapsize` 512 MB (chỉ áp dụng khi `largeHeap`)
- eMMC `DG4032`; phân vùng `/data` 16 GB, đã dùng 4,5 GB

## GPU và màn hình

- GPU: Vivante GC7000XSVX, OpenGL ES 3.2 (driver V6.2.4.p4), EGL 1.5
- Màn hình chính (`local:0`): 1920x720, 50 Hz, 160 dpi, cảm ứng `synaptics_dsx`
- Màn hình HDMI (`local:1`): 1920x720, 30 Hz
- Màn hình thứ ba (`local:2`): 648x608, 50 Hz
- Launcher nhúng app vào các màn hình ảo `ActivityView`: 1082x712, 339x712 và 1080x580 (của navi).
  Đây là các kích thước cửa sổ thực tế mà `DisplayModeHelper` cần xử lý
- Tính năng hệ thống có: `activities_on_secondary_displays`, cảm ứng đa điểm.
  Không có: `picture_in_picture`, `freeform`, `automotive`, `leanback`

## Giải mã video

Nguồn: `/vendor/etc/media_codecs.xml`, `media_codecs_google_video.xml`, `media_codecs_performance.xml`.

### Phần cứng (`OMX.Freescale.*.hw-based`)

| Codec | Tên decoder | Kích thước tối đa | Bitrate tối đa | Tốc độ đo của hãng |
|---|---|---|---|---|
| H.264 | `OMX.Freescale.std.video_decoder.avc.v3.hw-based` | 4096x2160 | 50 Mbps | 720p: 221 fps; 1080p: 107 fps |
| HEVC | `OMX.Freescale.std.video_decoder.hevc.hw-based` | 4096x2160 | 100 Mbps | 720p: 233 fps; 1080p: 132 fps; 2160p: 42 fps |
| VP8 | `OMX.Freescale.std.video_decoder.vp8.hw-based` | 1920x1088 | 40 Mbps | không có số đo |
| MPEG-4, MPEG-2, H.263, MJPEG | `...hw-based` | 1920x1088 | 20-40 Mbps | không cần cho YouTube |

Các decoder cứng có quirk `requires-allocate-on-input-ports` và `requires-allocate-on-output-ports`,
và **không** khai báo `adaptive-playback`.

### Phần mềm (`OMX.google.*`)

| Codec | Tốc độ đo của hãng | Kết luận |
|---|---|---|
| VP9 | 360p: 78 fps; 720p: 36 fps; 1080p: 21 fps | Chỉ an toàn đến 720p30 |
| H.264 mềm | 720p: 28 fps; 1080p: 12 fps | Không bao giờ được rơi vào decoder này |
| HEVC mềm | 720p: 41 fps; 1080p: 23 fps | Không dùng |
| VP8 mềm | 720p: 19 fps | Không dùng |

Không có decoder AV1 nào trên máy.

### Quy tắc chọn luồng YouTube rút ra

1. `avc1` ở mọi độ phân giải và tốc độ khung hình đều chạy cứng, kể cả 1080p60.
2. Nếu video không có `avc1` ở độ phân giải đang chọn, hạ độ phân giải để lấy `avc1` tốt hơn là giữ độ phân giải bằng VP9.
3. VP9 trên 720p30 và mọi AV1 phải coi là không phát được mượt.
4. Màn hình cao 720 px nên 1080p không cho thêm chi tiết nhìn thấy được, chỉ tốn băng thông.

## Âm thanh

- Các luồng ra của AudioFlinger: 44,1 kHz, 2 kênh, bộ đệm HAL 768 byte
- Decoder AAC, MP3, FLAC của Freescale đều là phần mềm

## Mạng

- `wlan0`: Wi-Fi tới hotspot điện thoại, 2,4 GHz (kênh 1), tốc độ liên kết 86 Mbps, RSSI khoảng -23 dBm,
  Android gắn nhãn "metered". Đây là mạng mặc định (score 200), có IPv4 và IPv6
- `tbox0`: Ethernet nội bộ tới T-Box của xe, có cờ `INTERNET` và `VALIDATED` (score 150).
  Chưa kiểm tra T-Box có thực sự cho ra Internet dùng được hay không
- `eth0` (10.0.0.1/24) và `usb0` (10.10.1.2/24): mạng nội bộ của xe
- Google Play services có trên máy (26.43.30), WebView hệ thống rất cũ (Chromium 66)

## CarTube trên xe lúc đo

- Gói cài: `app.revanced.android.youtube` (đây là `applicationId` của CarTube), 1.1.1 (versionCode 13) lúc đo; từ commit `1ff81f0` là 1.2.0 (versionCode 14),
  cập nhật lần cuối 2026-11-25 15:59, tức bản build từ nhánh `playback-buffer-prefetch`
- Bộ nhớ khi đang phát: PSS tổng 138 MB; Java heap 24 MB; native heap 57 MB
- Decoder đang dùng: `OMX.Freescale.std.video_decoder.avc.v3.hw-based`, luồng `avc1.4D401F` 1280x720@30
- CPU theo luồng khi đang phát (trên tổng 500%): main thread 31%, `RenderThread` 10%,
  `ExoPlayer:Playback` 7%, các luồng `MediaCodec_loop` gần 0%
- Nguồn phát: client `TVHTML5`, DASH MPD, `CronetDataSource`

### Việc cần xem tiếp, phát hiện trong lúc đo

- **Main thread ăn 31-37% CPU khi chỉ phát video, và video trong khung nhúng chỉ hiện khoảng 21 fps.**
  Nguyên nhân đã đo được: khi `PlayerActivity` chạy trong khung nhúng của launcher (màn hình ảo `ActivityView`),
  `attachPlayerToActiveView()` chuyển sang `TextureView`. Khi đó mỗi khung hình video buộc app vẽ lại toàn bộ
  giao diện (247 view). Số đo `dumpsys gfxinfo` trong 4 giây phát video 30 fps:
  - chỉ 86 khung hình giao diện được vẽ (khoảng 21 fps), 84% bị đánh dấu "janky"
  - mỗi khung mất trung vị 36 ms, trong đó `eglSwapBuffers` chiếm 22 ms (màn hình ảo chặn theo vsync),
    đo/bố cục 3,4 ms trên main thread
  - ExoPlayer không tính đây là rớt khung hình, nên log `DROPPED` không thấy
  Ở chế độ toàn màn hình app dùng `SurfaceView`, video đi thẳng ra bộ tổng hợp hình và không qua đường này.
  **Đã sửa ở commit `2751512` và đo lại trên xe (2026-11-25, bản thử cài qua adb):**
  - `SurfaceView` hiển thị đúng vị trí và đúng hình trong khung nhúng 1082x712 (đã chụp màn hình kiểm tra)
  - số khung hình giao diện app phải vẽ giảm từ khoảng 21-24 xuống 2 mỗi giây (chỉ còn thanh tiến trình)
  - lớp video hiện 23,1 fps cho video 24 fps (`dumpsys SurfaceFlinger --latency`), `droppedFrames=0`
  - CPU theo luồng đo trong cửa sổ 10 giây: main thread 8,8%, `ExoPlayer:Playback` 17,9%, cả app 41% trên 500%.
    Con số 31-37% ở trên lấy từ ảnh chụp tức thời của `top`, vốn dao động mạnh; cách đo ổn định hơn
    (`schedstat`, 5 giây) cho bản cũ khoảng 13%
  - đính chính (đo lại cùng ngày với video 30 fps): đổi sang `SurfaceView` **không** làm tăng số khung hình
    hiện ra màn hình. Video 30 fps vẫn chỉ hiện 21-24 fps. Con số 23,1 fps ở trên là của video 24 fps.
    Lợi ích thật của `SurfaceView` là app không còn phải vẽ lại giao diện theo từng khung hình video.
- **Khung nhúng của launcher bị giới hạn khoảng 24-25 khung hình/giây.** Đo bằng
  `dumpsys SurfaceFlinger --latency` khi phát video 30 fps trong khung nhúng:
  - lớp video của CarTube: 24,4 fps; bề mặt của launcher chứa khung nhúng đó: 24,4 fps, cùng phân bố khoảng cách
    khung hình (chủ yếu 40 ms, tức mỗi hai nhịp quét 50 Hz một lần)
  - Google Maps trong khung nhúng của nó: 20 fps; `surfaceflinger` thường xuyên dùng 45-50% CPU
  - giả thuyết "ExoPlayer canh theo 60 Hz" **sai**: thời điểm ExoPlayer yêu cầu hiện khung hình khớp hoàn toàn
    với lưới 20 ms (50 Hz), không khớp lưới 16,67 ms
  - hệ quả: video 24/25 fps gần như đủ khung hình; video 30 và 60 fps mất khoảng 20% trở lên và nhịp không đều.
    Đây là giới hạn của đường hiển thị màn hình ảo trên xe, app không điều khiển được
  - chưa đo: chế độ toàn màn hình trên màn hình chính ("Toàn xe") có đạt đủ 30 fps hay không
- **Một lần tụt chất lượng xuống 144p-240p (chưa rõ nguyên nhân).** Bài `i4XmH13A7lI` lúc 16:40: bắt đầu ở
  1920x804 (3,2 Mbps), 12 giây sau xuống 1280x536, 54 giây sau xuống 256x128 rồi giữ 426x178 đến hết bài; băng
  thông ước lượng cuối bài 566 kbps trong khi các bài khác là 20-44 Mbps. Không có lần rebuffer nào. Bài kế tiếp
  trở lại 720p sau 15 giây. Hoặc mạng 4G yếu thật trong 3,5 phút, hoặc YouTube bóp tốc độ luồng đó; một mẫu chưa
  đủ kết luận. Xem lại bằng các dòng `FORMAT` và `bandwidthKbps` trong log `CarTubeHealth` nếu lặp lại.
- **Khởi động bài chậm:** trong log, từ lúc dựng xong nguồn DASH đến lúc decoder khởi tạo mất khoảng 5 giây
  (16:00:44,5 đến 16:00:49,5). Mới có một mẫu, cần đo thêm.
- **Lỗi giả khi đổi bài nhanh (đã sửa trong code, chưa build):** log có
  `Failed to prepare video ...: StandaloneCoroutine was cancelled`. `loadJob` bị hủy khi người dùng chọn bài
  khác, nhưng khối `catch (e: Exception)` trong `playVideoInternal` bắt luôn `CancellationException` và ghi
  thông báo lỗi vào trạng thái của bài mới.
- **README ghi "H.264/VP9 phần cứng"** nhưng máy này không giải mã cứng VP9.

## Cách đo lại

Kết nối (IP do hotspot cấp nên có thể đổi; cổng cố định):

```
adb connect <ip-xe>:5578
```

| Cần xem | Lệnh |
|---|---|
| Model, Android, heap | `adb shell getprop` (lọc `ro.product`, `ro.build.version`, `dalvik.vm.heap`) |
| Nhân CPU | `adb shell cat /proc/cpuinfo` và `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` |
| RAM | `adb shell cat /proc/meminfo` |
| Màn hình | `adb shell dumpsys display` (lọc `DisplayDeviceInfo`) |
| Decoder | `adb shell cat /vendor/etc/media_codecs.xml /vendor/etc/media_codecs_performance.xml` |
| Nhiệt độ | `adb shell 'for z in /sys/class/thermal/thermal_zone*; do cat $z/type $z/temp; done'` |
| Mạng | `adb shell dumpsys connectivity` (lọc `NetworkAgentInfo`) |
| CPU theo luồng của app | `adb shell 'top -H -b -n 1 -p $(pidof app.revanced.android.youtube)'` |
| Sức khỏe phát video | `adb logcat -s CarTubeHealth CarPlaybackService` |

Nhiệt độ lúc đo (máy mới bật 8 phút): CPU 43 °C, GPU 43 °C, PMIC 70 °C.
