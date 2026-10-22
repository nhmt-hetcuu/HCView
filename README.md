# HCView — Remote Screen & Control Companion for ESP32 Handheld Devices

<p align="center">
  <strong>Giải pháp truyền màn hình và điều khiển từ xa đa nền tảng (Android & Windows) dành cho thiết bị vi điều khiển cầm tay (ESP32)</strong><br />
  🌐 Website chính thức: <a href="https://hetcuu.com">hetcuu.com</a>
</p>

<p align="center">
  <a href="https://hetcuu.com"><img src="https://img.shields.io/badge/Website-hetcuu.com-0284c7?style=flat" alt="Website hetcuu.com" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Windows-blue" alt="Platform" />
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green" alt="Android" />
  <img src="https://img.shields.io/badge/Python-3.10%2B-yellow" alt="Python" />
  <img src="https://img.shields.io/badge/License-MIT-red" alt="License" />
</p>

---

## 📖 Giới thiệu tổng quan

**HCView** là hệ thống phần mềm đóng vai trò máy chủ trung tâm (**"PC"** / `vai_tro=pc`) trong giao thức Remote PC kết nối với thiết bị cầm tay sử dụng chip vi điều khiển ESP32:

1. **Truyền màn hình thời gian thực (Host → ESP32):** Chụp một vùng màn hình từ máy tính hoặc điện thoại, tối ưu độ tương phản, co về đúng độ phân giải vật lý của màn hình LCD (**191 × 62 pixel**) và đóng gói nhị phân gửi xuống thiết bị qua WebSocket.
2. **Nhận lệnh & điều khiển ngược (ESP32 → Host):** Nhận các phím bấm và thao tác điều hướng từ bàn phím thiết bị, mô phỏng chính xác chuột, cảm ứng chạm, cuộn trang, zoom và gõ bàn phím tiếng Việt Unicode trên máy tính hoặc điện thoại.

Dự án bao gồm 2 nền tảng máy chủ độc lập:
- 📱 **[HCView Android](./Android):** Ứng dụng Android (Kotlin) chạy ngầm, chụp màn hình qua `MediaProjection` và điều khiển cảm ứng qua `AccessibilityService`.
- 💻 **[HCView Windows](./Windows):** Ứng dụng máy tính (Python + PyQt6) chụp màn hình tốc độ cao qua MSS và mô phỏng chuột/phím chuẩn xác bằng Windows `SendInput` API.

---

## ⚡ Các tính năng chung

- **Hai chế độ kết nối linh hoạt:**
  - **Chế độ 1 — Nối thẳng (Direct Server - Khuyên dùng):** Ứng dụng tự mở WebSocket Server (mặc định cổng `8268`). Thiết bị ESP32 và Host chỉ cần cùng kết nối một mạng WiFi hoặc kết nối qua Điểm phát sóng di động (Hotspot).
  - **Chế độ 2 — Qua Server Relay:** Hỗ trợ kết nối qua Internet khi 2 thiết bị ở 2 mạng khác nhau thông qua Cloud Relay Server với xác thực JWT Token.
- **Tối ưu hiển thị cho màn hình LCD 191 × 62:**
  - Hỗ trợ **2-bit grayscale** (4 mức xám chuẩn LCD) và **1-bit monochrome** (đơn sắc tương phản cao).
  - Bộ lọc chống mờ chữ (Unsharp Mask & High Contrast) giúp văn bản, công thức toán học và tài liệu luôn sắc nét, không bị mất nét khi thu nhỏ.
  - Tỉ lệ khung hình chuẩn ~3.08:1, hỗ trợ các mức Zoom dải ngang $1.0\times$, $1.5\times$, $2.0\times$, $3.0\times$.
- **Hỗ trợ gõ tiếng Việt Unicode:** Gõ trực tiếp văn bản có dấu (Telex/VNI) từ thiết bị vào mọi ô nhập liệu trên Host.
- **Tiết kiệm tài nguyên:** Tự động lọc khung hình tĩnh, chỉ gửi dữ liệu khi nội dung thay đổi hoặc theo chu kỳ nhịp giữ sống (keepalive).

---

## 📂 Cấu trúc mã nguồn

```text
HCView/
├── Android/                    # Ứng dụng Android (Kotlin / Material 3)
│   ├── app/                    # Mã nguồn ứng dụng Android
│   │   ├── src/main/           # Mã nguồn Kotlin & tài nguyên XML
│   │   └── src/test/           # Unit test bộ giải mã lệnh & đóng gói khung
│   ├── gradle/wrapper/         # Gradle Wrapper 8.13
│   ├── build.gradle.kts        # Cấu hình Gradle
│   ├── settings.gradle.kts     # Thiết lập Gradle settings
│   ├── gradlew / gradlew.bat   # Script build Gradle trên Linux / Windows
│   ├── .gitignore              # Loại trừ build cache & local configs
│   └── README.md               # Hướng dẫn chi tiết cho nền tảng Android
│
├── Windows/                    # Ứng dụng Windows (Python / PyQt6)
│   ├── main.py                 # Mã nguồn giao diện & logic điều khiển máy tính
│   ├── requirements.txt        # Danh sách thư viện Python (PyQt6, websockets, numpy)
│   ├── remote_config.json      # Cấu hình lưu trữ (vùng chụp, cổng, tài khoản...)
│   └── README.md               # Hướng dẫn chi tiết cho nền tảng Windows
│
├── ESP32/                      # Mã nguồn mẫu cho vi điều khiển ESP32
│   ├── esp32_hcview_example.ino # Example kết nối, nhận màn hình & gửi lệnh
│   └── README.md               # Hướng dẫn cài đặt thư viện & nạp code ESP32
│
├── .gitignore                  # Quy tắc loại trừ tệp rác toàn bộ dự án
├── LICENSE                     # Giấy phép nguồn mở MIT License
└── README.md                   # Tài liệu tổng quan dự án
```


---

## 🚀 Hướng dẫn nhanh

### 1. Sử dụng phiên bản Android
Xem hướng dẫn chi tiết tại [Android/README.md](./Android/README.md).
- Yêu cầu: Android 8.0 trở lên (API 26+).
- Cài đặt APK hoặc mở thư mục `Android` bằng Android Studio rồi bấm **Run**.
- Cấp quyền **Trợ năng (Accessibility Service)** và quyền **Ghi màn hình**.
- Bấm **Bắt đầu chia sẻ**, nhập địa chỉ IP hiển thị trên ứng dụng vào thiết bị ESP32.

### 2. Sử dụng phiên bản Windows
Xem hướng dẫn chi tiết tại [Windows/README.md](./Windows/README.md).
- Yêu cầu: Windows 10 / 11, Python 3.10+.
- Cài đặt thư viện:
  ```bash
  cd Windows
  pip install -r requirements.txt
  ```
- Khởi chạy ứng dụng:
  ```bash
  python main.py
  ```
- Chọn vùng màn hình cần truyền, bấm **Bắt đầu chia sẻ** và nhập IP hiển thị vào thiết bị ESP32.

### 3. Thao tác kết nối trên thiết bị ngoại vi (ESP32)
1. Mở tính năng **Remote PC** trên thiết bị.
2. Bấm phím **Tools** → Chọn **"Doi dia chi PC/server"**.
3. Ô **PC/server**: Nhập IP & Cổng hiển thị trên máy chủ (ví dụ: `192.168.1.25:8268`).
4. Ô **Device ID**: Để trống khi dùng chế độ Nối thẳng (Direct).
5. Bấm **OK** để kết nối.

---

## 📡 Giao thức truyền thông (Protocol)

### 1. Định dạng khung hình (Host → ESP32)
- **Độ phân giải:** $191 \times 62$ pixel.
- **1-bit (Monochrome):** 1.488 byte / khung (8 pixel / byte, MSB bên trái).
- **2-bit (Grayscale):** 2.976 byte / khung (2 bit / pixel: `00` trắng, `01` xám nhạt, `10` xám đậm, `11` đen).

### 2. Bảng lệnh điều khiển (ESP32 → Host)
Lệnh gửi qua WebSocket dưới dạng chuỗi UTF-8:

| Lệnh | Ý nghĩa | Hành động trên Windows | Hành động trên Android |
|:---:|---|---|---|
| `C<x>,<y>` | Chạm / Click | Click chuột trái tại tọa độ $(x, y)$ | Chạm (Tap) tại tọa độ $(x, y)$ |
| `P<x>,<y>` | Nhấn giữ | Click chuột phải tại $(x, y)$ | Nhấn giữ (Long press) tại $(x, y)$ |
| `S<delta>` | Cuộn trang | Lăn con lăn chuột (scroll wheel) | Vuốt cuộn trang (Accessibility scroll) |
| `V<mức>` | Tốc độ cuộn | Đổi hệ số cuộn (`V0.5` – `V2.0`) | Đổi tốc độ cuộn màn hình |
| `M<hx>,<hy>`| Dời vùng | Dời vùng chụp theo hướng $[-1, 0, 1]$ | Dời vùng chụp màn hình |
| `Z<mức>` | Thu phóng | Đổi mức zoom ($1.0\times$ – $3.0\times$) | Đổi mức thu phóng vùng chụp |
| `T<chuỗi>` | Gõ văn bản | Gõ văn bản Unicode tiếng Việt | Gõ văn bản tiếng Việt có dấu |
| `KESC` | Phím Esc | Bấm phím Esc | Hành động Quay lại (Back) |
| `KENTER` | Phím Enter | Bấm phím Enter | Bấm phím Enter |
| `KBACKSPACE`| Xóa lùi | Bấm phím Backspace | Bấm phím Xóa lùi |
| `KTAB` | Phím Tab | Bấm phím Tab | Chuyển tiêu điểm tiếp theo |
| `KHOMESCREEN`| Phím Home | Mở Start Menu (Phím Win) | Quay về màn hình chính |
| `KRECENTS` | Đa nhiệm | Mở Task View (Win + Tab) | Mở danh sách ứng dụng gần đây |

---

## 🌐 Tác giả & Website

- **Website chính thức:** [hetcuu.com](https://hetcuu.com)
- **Cộng đồng & Hỗ trợ:** Cập nhật phiên bản mới nhất, hướng dẫn phần cứng và thảo luận kỹ thuật tại [https://hetcuu.com](https://hetcuu.com).

---

## 📄 Giấy phép (License)

Dự án được phát hành theo giấy phép **[MIT License](./LICENSE)**. Bạn hoàn toàn có quyền sử dụng, sửa đổi và phân phối lại cho mục đích học tập và nghiên cứu.
# HCView
