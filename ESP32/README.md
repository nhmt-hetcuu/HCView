# HCView — ESP32 Client Example

Ví dụ mẫu mã nguồn C/C++ (Arduino / PlatformIO) dành cho vi điều khiển **ESP32** (ESP32-WROOM, ESP32-S2, ESP32-S3, ESP32-C3...) kết nối trực tiếp với ứng dụng máy chủ **HCView** (Android / Windows).

---

## 📋 Tính năng của Example

1. **Kết nối WiFi:** Tự động kết nối vào mạng WiFi hoặc Hotspot phát từ điện thoại / máy tính.
2. **WebSocket Client:** Kết nối trực tiếp vào máy chủ HCView cổng `8268`.
3. **Nhận luồng màn hình nhị phân 191 × 62:**
   - Hỗ trợ chế độ **1-bit monochrome** ($1.488\text{ bytes}$).
   - Hỗ trợ chế độ **2-bit grayscale** ($2.976\text{ bytes}$, 4 mức xám).
   - Có sẵn 2 hàm giải mã điểm ảnh `getPixel1Bit()` và `getPixel2Bit()` để dễ dàng nạp vào bộ nhớ màn hình LCD / OLED (ST7567, SSD1306, ST7789, ST7735...).
4. **Gửi lệnh điều khiển:**
   - Chạm / Click: `sendTap(x, y)` $\rightarrow$ `C<x>,<y>`
   - Nhấn giữ: `sendLongPress(x, y)` $\rightarrow$ `P<x>,<y>`
   - Cuộn trang: `sendScroll(delta)` $\rightarrow$ `S<delta>`
   - Đổi tốc độ cuộn: `sendScrollSpeed(speed)` $\rightarrow$ `V<speed>`
   - Dời vùng chụp: `sendPan(hx, hy)` $\rightarrow$ `M<hx>,<hy>`
   - Thu phóng: `sendZoom(zoom)` $\rightarrow$ `Z<zoom>`
   - Gõ văn bản Unicode tiếng Việt: `sendText("...")` $\rightarrow$ `T<text>`
   - Phím chức năng: `sendKey("...")` $\rightarrow$ `KESC`, `KENTER`, `KBACKSPACE`, `KTAB`, `KHOMESCREEN`, `KRECENTS`
5. **Điều khiển thử nghiệm qua Serial Monitor (115200 baud):**
   - Phím `c`: Chạm vào chính giữa màn hình.
   - Phím `s`: Cuộn trang xuống.
   - Phím `t`: Gõ một chuỗi văn bản mẫu.
   - Phím `h`: Bấm phím Home / Start.

---

## 🛠️ Cài đặt & Nạp chương trình

### 1. Dùng Arduino IDE
1. Cài đặt board **ESP32 by Espressif Systems** trong Boards Manager.
2. Cài đặt các thư viện sau từ Library Manager:
   - **WebSockets** (bởi Markus Sattler)
   - **ArduinoJson** (bởi Benoit Blanchon)
3. Mở file `esp32_hcview_example.ino`.
4. Điền cấu hình WiFi và IP máy chủ:
   ```cpp
   const char* WIFI_SSID     = "TÊN_WIFI_CỦA_BẠN";
   const char* WIFI_PASSWORD = "MẬT_KHẨU_WIFI";
   const char* HC_SERVER_IP  = "192.168.1.15"; // IP hiển thị trên app HCView
   ```
5. Chọn đúng cổng COM và board ESP32 của bạn $\rightarrow$ Bấm **Upload**.

### 2. Dùng PlatformIO (`platformio.ini`)
```ini
[env:esp32]
platform = espressif32
board = esp32dev
framework = arduino
monitor_speed = 115200
lib_deps =
    links2004/WebSockets@^2.4.1
    bblanchon/ArduinoJson@^7.0.0
```

---

## 🌐 Website & Tác giả

- **Trang chủ:** [https://hetcuu.com](https://hetcuu.com)

