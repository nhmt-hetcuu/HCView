# HCView — Ứng dụng Android điều khiển & truyền màn hình cho thiết bị ESP32

Ứng dụng Android đóng vai trò **"PC"** trong giao thức Remote PC (`vai_tro=pc`) kết nối với thiết bị vi điều khiển cầm tay (**ESP32**):

1. **Truyền màn hình (Android → ESP32):** Chụp một vùng màn hình điện thoại (MediaProjection) → cắt và co về kích thước chuẩn **191 × 62 pixel** → đóng gói nhị phân thành **1.488 byte** (chế độ 1-bit) hoặc **2.976 byte** (chế độ 2-bit, 4 mức xám chuẩn LCD) → truyền xuống thiết bị qua WebSocket.
2. **Nhận lệnh điều khiển (ESP32 → Android):** Tiếp nhận các lệnh chạm (`C`), nhấn giữ (`P`), cuộn trang (`S`), dời vùng chụp (`M`), gõ chữ (`T`), phím điều hướng (`K`) từ bàn phím thiết bị và mô phỏng trên điện thoại thông qua **Accessibility Service**.

---

## 📱 Tính năng nổi bật

- **Hai chế độ kết nối linh hoạt:**
  - **Chế độ A — Nối thẳng (Khuyên dùng):** Điện thoại đóng vai trò WebSocket Server (cổng `8268`). Tự động phát hiện và hiển thị mọi địa chỉ IPv4 (WiFi nội bộ hoặc Điểm phát sóng di động Hotspot của điện thoại) kèm nút bấm 1-chạm để sao chép địa chỉ nhập vào thiết bị.
  - **Chế độ B — Qua Server Relay:** Hỗ trợ kết nối qua Internet/4G bằng server trung gian, xác thực REST API (JWT Token), quản lý danh sách máy và tự động kết nối lại khi mất mạng.
- **Theo dõi thiết bị kết nối theo thời gian thực:**
  - Tự động nhận diện và hiển thị địa chỉ IP thực tế của thiết bị ngoại vi (ESP32 Client) ngay khi kết nối thành công, kèm badge trạng thái `ONLINE/OFFLINE` và nút sao chép 1-chạm.
- **Mô phỏng màn hình LCD ma trận điểm theo thời gian thực:**
  - Hiển thị trực tiếp khung ảnh 191×62 với 4 mức xám LCD ngay trên giao diện ứng dụng để bạn kiểm tra chính xác những gì thiết bị đang nhìn thấy.
- **Khung viền trực quan (Accessibility Overlay):**
  - Hiển thị một khung viền mờ đánh dấu vùng màn hình đang được truyền tới thiết bị, sử dụng `TYPE_ACCESSIBILITY_OVERLAY` mà không yêu cầu thêm quyền phiền toái.
- **Tuỳ chỉnh vùng chụp tỉ lệ ~3:1:**
  - Hỗ trợ các nút chọn nhanh (Dải Trên, Dải Giữa, Dải Dưới) và thanh trượt tinh chỉnh độ dời Y mượt mà.
- **Lượng tử hoá hình ảnh tối ưu:**
  - Lựa chọn 2-bit (4 mức xám mịn đẹp) hoặc 1-bit (đơn sắc tương phản cao), tuỳ chọn đảo màu đen/trắng, bộ lọc khung trùng và nhịp giữ kết nối (keepalive).
- **Hỗ trợ đầy đủ bộ gõ tiếng Việt Unicode và phím chức năng:**
  - Tích hợp chuẩn `InputMethod` (Android 13+) và fallback cho Android 12 trở xuống.

---

## 🏗️ Cấu trúc dự án

```text
Android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml             # Khai báo quyền, Services & Activity
│   │   │   ├── java/com/hetcuu/hcview/
│   │   │   │   ├── HcViewApp.kt                # Khởi tạo Application & Notification Channel
│   │   │   │   ├── model/
│   │   │   │   │   ├── RemoteConfig.kt         # Lưu trữ cấu hình ứng dụng (SharedPreferences)
│   │   │   │   │   └── AppState.kt             # Quản lý trạng thái kết nối & số liệu (StateFlow)
│   │   │   │   ├── protocol/
│   │   │   │   │   ├── FramePacker.kt          # Đóng gói khung 1488B/2976B & giải mã LCD preview
│   │   │   │   │   └── CommandDispatcher.kt    # Phân tích cú pháp lệnh C/P/S/V/M/Z/T/K
│   │   │   │   ├── network/
│   │   │   │   │   ├── DirectServer.kt         # WebSocket Server chế độ nối thẳng (Java-WebSocket)
│   │   │   │   │   ├── RelayClient.kt          # WebSocket Client chế độ Relay qua cloud (OkHttp)
│   │   │   │   │   └── NetworkUtils.kt         # Lấy IPv4 nội bộ, quản lý WiFi/Wake Lock
│   │   │   │   ├── service/
│   │   │   │   │   ├── CaptureService.kt       # MediaProjection Foreground Service chụp màn hình
│   │   │   │   │   └── RemoteA11yService.kt    # AccessibilityService thực thi cử chỉ & gõ chữ
│   │   │   │   └── ui/
│   │   │   │       ├── LcdMatrixView.kt        # Custom View mô phỏng màn hình LCD 191x62
│   │   │   │       └── MainActivity.kt         # Giao diện chính Material Design 3
│   │   │   └── res/                            # Giao diện XML, biểu tượng và tài nguyên
│   │   └── test/java/
│   │       ├── android/graphics/Rect.java      # Mock stub cho unit test
│   │       └── com/hetcuu/hcview/
│   │           ├── FramePackerTest.kt          # Unit test nén khung hình 1-bit & 2-bit
│   │           └── CommandDispatcherTest.kt    # Unit test biên dịch lệnh điều khiển
│   ├── build.gradle.kts                        # Cấu hình build module app
│   └── .gitignore
├── gradle/wrapper/                             # Gradle Wrapper (Gradle 8.13)
├── build.gradle.kts                            # Cấu hình build gốc
├── settings.gradle.kts                         # Cấu hình dự án & repository
├── gradle.properties                           # Tham số JVM và AndroidX
├── gradlew                                     # Script chạy Gradle trên Linux/macOS
├── gradlew.bat                                 # Script chạy Gradle trên Windows
├── .gitignore                                  # Quy tắc loại trừ tệp rác Android Studio / Gradle
└── README.md                                   # Tài liệu hướng dẫn Android
```

---

## 🚀 Hướng dẫn cài đặt và sử dụng

### 1. Yêu cầu hệ thống
- **Hệ điều hành:** Android 8.0 (API 26) trở lên (Khuyến nghị Android 13+ / API 33+).
- **Quyền yêu cầu:**
  - **Dịch vụ Hỗ trợ tiếp cận (Accessibility Service):** Cần bật để mô phỏng cử chỉ chạm, vuốt và nhập văn bản.
  - **Ghi / Chụp màn hình (MediaProjection):** Cho phép chụp vùng màn hình cần truyền.
  - **Thông báo (Notifications):** Duy trì Foreground Service ổn định trong nền.

### 2. Thao tác trên điện thoại Android
1. Mở ứng dụng **HCView** trên điện thoại.
2. Nếu có thông báo yêu cầu Trợ năng, bấm **Mở Cài đặt Trợ năng** → chọn **HCView** → **Bật dịch vụ**.
3. Tại giao diện chính:
   - Chọn chế độ **Nối thẳng (Direct)** hoặc **Server Relay**.
   - Điều chỉnh vị trí vùng chụp (Dải trên / giữa / dưới hoặc kéo thanh trượt).
   - Chọn độ sâu màu (2-bit hoặc 1-bit) và tốc độ khung hình (1 - 30 fps).
   - Bấm nút **Bắt đầu chia sẻ** và đồng ý cấp quyền chụp màn hình.

### 3. Thao tác trên thiết bị cầm tay (ESP32)
1. Mở tính năng **Remote PC** trên thiết bị.
2. Chọn mục đổi địa chỉ PC/Server.
3. Ô **PC/server**: nhập địa chỉ IP hiển thị trên điện thoại (ví dụ: `192.168.1.25:8268` hoặc IP Hotspot `192.168.43.1:8268`).
4. Ô **Device ID**: **Để trống** (để chế độ nối thẳng hoạt động không cần khóa API).
5. Xác nhận kết nối. Thiết bị sẽ tự động ghép cặp và bắt đầu hiển thị màn hình điện thoại!

---

## 🎮 Bảng điều khiển từ thiết bị ngoại vi

Thiết bị ngoại vi (ESP32) gửi các lệnh điều khiển dạng chuỗi UTF-8 lên Android qua WebSocket:

| Mã lệnh | Thao tác trên thiết bị | Thao tác thực thi trên điện thoại Android |
|:---:|---|---|
| `C<x>,<y>` | Bấm phím **OK** (Chế độ con trỏ) | Chạm (Tap) tại tọa độ quy đổi tương ứng trong vùng chụp |
| `P<x>,<y>` | Bấm **Page Down** hoặc Tools > "Chuot phai" | Nhấn giữ (Long press) tại tọa độ quy đổi (Mở menu ngữ cảnh) |
| `S<delta>` | Phím **Lên / Xuống** (Chế độ cuộn) | Cuộn trang (Scroll up / down) theo bước cuộn |
| `V<mức>` | Tools > "Toc do cuon..." / Phím tắt Scroll | Điều chỉnh tốc độ cuộn (`V0.5`, `V1`, `V1.5`, `V2` hoặc `V+`, `V-`) |
| `M<hx>,<hy>`| Mũi tên chạm mép / Đổi vùng | Dời vùng chụp (Pan) lên / xuống / trái / phải |
| `Z<mức>` | Tools > "Thu phong..." / Phím tắt Zoom | Thu phóng dải ngang (`Z1`, `Z1.5`, `Z2`, `Z+`, `Z-`) |
| `T<chuỗi>` | Tools > "Go chu..." | Gõ trực tiếp chuỗi ký tự UTF-8 (hỗ trợ tiếng Việt có dấu) |
| `KESC` | Phím Esc | Gửi hành động quay lại (BACK) |
| `KENTER` | Phím Enter | Gửi phím Enter / Xác nhận |
| `KBACKSPACE`| Phím Xóa lùi | Xóa lùi ký tự |
| `KTAB` | Phím Tab | Chuyển tiêu điểm tiếp theo |
| `KHOMESCREEN`| Phím Home | Quay về màn hình chính (GLOBAL_ACTION_HOME) |
| `KRECENTS` | Phím Đa nhiệm | Mở danh sách ứng dụng gần đây (GLOBAL_ACTION_RECENTS) |

---

## 📦 Định dạng khung hình (Frame Protocol)

Khung hình được gửi dưới dạng gói tin nhị phân (Binary WebSocket message):
- **Kích thước ảnh:** Cố định **191 × 62** pixel.
- **Chế độ 1-bit (Đơn sắc):** 
  - $191 \times 62 = 11.842$ bit $\approx 1.481$ byte $\rightarrow$ Đóng gói chẵn **1.488 byte** (mỗi byte chứa 8 pixel ngang, MSB bên trái).
- **Chế độ 2-bit (4 mức xám chuẩn LCD):**
  - Mỗi pixel chiếm 2 bit: `00` = Trắng (nền LCD), `01` = Xám nhạt, `10` = Xám đậm, `11` = Đen.
  - Tổng kích thước: $191 \times 62 \times 2 = 23.684$ bit $\rightarrow$ Đóng gói chẵn **2.976 byte**.

---

## 🔨 Hướng dẫn biên dịch (Build & Test)

### 1. Dùng Android Studio (Khuyên dùng)
1. Mở Android Studio, chọn **File > Open** và trỏ đến thư mục `Android`.
2. Chờ Gradle tải dependencies và đồng bộ dự án.
3. Kết nối điện thoại hoặc máy ảo, nhấn **Run (Shift + F10)**.

### 2. Dùng dòng lệnh (CLI / Terminal)
Yêu cầu đã cài đặt **JDK 17** hoặc **JDK 21** và cấu hình biến môi trường `ANDROID_HOME`.

- **Chạy kiểm thử tự động (Unit Tests):**
  ```bash
  # Windows:
  .\gradlew.bat test

  # Linux / macOS:
  ./gradlew test
  ```

- **Biên dịch bản Debug APK:**
  ```bash
  # Windows:
  .\gradlew.bat assembleDebug

  # Linux / macOS:
  ./gradlew assembleDebug
  ```
  File APK đầu ra nằm tại: `app/build/outputs/apk/debug/app-debug.apk`

- **Cài đặt trực tiếp qua ADB:**
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

- **Dọn dẹp thư mục build:**
  ```bash
  .\gradlew.bat clean
  ```

---

## 🌐 Website & Tác giả

- **Trang chủ:** [https://hetcuu.com](https://hetcuu.com)

---

## 📄 Bản quyền
Dự án được phát hành theo giấy phép **MIT License**. Xem chi tiết tại tệp [LICENSE](../LICENSE).
