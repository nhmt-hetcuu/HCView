# HCView - Ứng dụng truyền màn hình & điều khiển máy tính từ xa (Windows)

**HCView** là ứng dụng tất cả trong một (All-in-One) dành cho Windows, cho phép truyền trực tiếp một vùng màn hình máy tính xuống thiết bị qua giao thức WebSocket (định dạng 2-bit, 4 mức xám chuẩn LCD), đồng thời nhận và thực thi các lệnh điều khiển chuột, con lăn, bàn phím và gõ văn bản từ thiết bị gửi lên.

---

## 1. Yêu cầu & Cài đặt

- **Hệ điều hành**: Windows 10 / 11 (hỗ trợ mô phỏng chuột/phím chuẩn xác qua Windows `SendInput`).
- **Python**: Phiên bản 3.10 trở lên.

### Cài đặt thư viện:
```bash
pip install -r requirements.txt
```

### Khởi chạy:
```bash
python main.py
```
*Lưu ý: Ứng dụng sẽ tự động kích hoạt hộp thoại UAC của Windows yêu cầu quyền Administrator khi mở để đảm bảo mô phỏng chuột/phím có thể điều khiển được tất cả các cửa sổ (kể cả Task Manager, CMD Admin...). Nếu từ chối, ứng dụng vẫn tiếp tục chạy với quyền người dùng thông thường.*

---

## 2. Kết nối thiết bị

Ứng dụng hỗ trợ 2 phương thức kết nối:

### Chế độ 1: Nối thẳng (Direct WebSocket Server - Khuyên dùng)
- Máy tính tự động mở WebSocket Server ngay khi chạy app (mặc định cổng `8268`).
- Thiết bị và máy tính kết nối chung mạng WiFi hoặc Hotspot phát từ máy tính.
- Trên thanh tiêu đề ứng dụng có sẵn dòng **`Địa chỉ: <ip>:<cổng>`** kèm nút **Sao chép**. Chỉ cần nhập địa chỉ này vào thiết bị để kết nối.
- Thiết bị kết nối vào lúc nào cũng được. Khi bấm **Bắt đầu chia sẻ**, hình ảnh được gửi xuống ngay lập tức. Dừng chia sẻ vẫn giữ kết nối chờ.

### Chế độ 2: Qua Server Relay (Internet / Khác mạng)
- Dành cho trường hợp thiết bị và máy tính ở hai mạng khác nhau qua server trung gian.
- Bấm nút **Cài đặt kết nối...**, chọn mục **Kết nối qua Server Relay**, nhập địa chỉ server, tài khoản và chọn thiết bị trong danh sách.
- Ứng dụng tự động quản lý phiên làm việc và gia hạn token khi hết hạn.

---

## 3. Quản lý vùng chụp màn hình

Màn hình thiết bị có tỉ lệ chuẩn xấp xỉ **3.08 : 1 (191 × 62 điểm)**:

- **Chọn vùng bằng chuột...**: Mở lớp phủ bán trong suốt dạng Snipping Tool để kéo rê chuột chọn vùng cần truyền. Mặc định khung chọn tự động khóa theo đúng tỉ lệ chuẩn `191:62` để chống méo chữ (nhấn giữ `Shift` nếu muốn kéo tự do).
- **Thu phóng (Zoom 1x / 1.5x / 2x / 3x)**:
  - **`1.0x` (Bao quát)**: Vùng $382 \times 124\text{ px}$ (co 2.0x, hiển thị diện tích rộng).
  - **`1.5x` (To rõ - Khuyên dùng)**: Vùng $286 \times 93\text{ px}$ (co 1.5x, chữ to đậm, đọc cực kỳ rõ nét).
  - **`2.0x` (Phóng to)**: Vùng $240 \times 78\text{ px}$ (chữ rất to).
  - **`3.0x` (To nhất 1:1)**: Vùng $191 \times 62\text{ px}$ (chuẩn 1:1 điểm ảnh vật lý, không co giãn).
- **Nút Căn giữa**: Dời vùng chụp hiện tại vào chính giữa màn hình máy tính.
- **Nút Khớp tỉ lệ 191:62**: Giữ nguyên tâm và bề ngang, tự động tính lại chiều cao chuẩn $H = \text{round}(W \times 62 / 191)$.

---

## 4. Xử lý hình ảnh & Tối ưu hiển thị

| Mục cài đặt | Chi tiết |
|---|---|
| **Độ sâu màu** | Cố định **2-bit** (4 mức xám chuẩn LCD: Nền, Xám nhạt, Xám đậm, Đen; đúng 2.976 byte/khung). |
| **Tăng nét chữ** | **Cân bằng** (mặc định) / **Cao** / **Tắt**. Sử dụng bộ lọc Unsharp Mask $3 \times 3$ kết hợp đường cong tương phản phi tuyến tính, giúp nét chữ thanh mảnh trên máy tính khi thu nhỏ vẫn đậm đà, không bị mất nét hay đứt đoạn. |
| **Đảo màu** | Chuyển đổi giữa nền sáng chữ đen và nền đen chữ trắng (Dark Mode). |
| **Tốc độ gửi** | Tùy chỉnh $1 - 30\text{ fps}$ (mặc định $5\text{ fps}$, khuyên dùng $5 - 10\text{ fps}$ để tối ưu băng thông). |

- **Cơ chế gửi thông minh**: Khung hình không thay đổi sẽ không gửi lại để tiết kiệm mạng (trừ nhịp giữ sống mỗi 2 giây). Khi dời vùng hoặc đổi zoom, khung mới được chụp và gửi ngay lập tức. Mạng chậm sẽ tự động bỏ khung cũ, chống hiện tượng trễ dồn hình ảnh.

---

## 5. Bảng lệnh điều khiển từ xa

Thiết bị có thể gửi các lệnh chuỗi UTF-8 đơn giản lên máy tính qua WebSocket:

| Thao tác thiết bị | Mã lệnh | Thao tác thực thi trên PC |
|:---:|:---:|---|
| Bấm phím chọn | `C<x>,<y>` | Click chuột trái tại tọa độ quy đổi tương ứng trong vùng chụp |
| Nhấn giữ / chuột phải | `P<x>,<y>` | Click chuột phải tại tọa độ quy đổi |
| Cuộn trang | `S<delta>` | Lăn con trỏ chuột (`S60` cuộn xuống, `S-60` cuộn lên) theo tốc độ cuộn |
| Đổi tốc độ cuộn | `V<mức>` | Điều chỉnh hệ số tốc độ cuộn (`V0.5` đến `V2.0`, hoặc `V+`, `V-`) |
| Dời vùng chụp | `M<hx>,<hy>` | Dời vùng nhìn theo hướng (`-1`, `0`, `1`), bước dời $50\%$ kích thước vùng |
| Thu phóng | `Z<mức>` | Đổi mức zoom dải ngang (`Z1.0`, `Z1.5`, `Z2.0`, `Z3.0` hoặc `Z+`, `Z-`) |
| Gõ văn bản | `T<chuỗi>` | Gõ trực tiếp chuỗi ký tự Unicode (tiếng Việt có dấu Telex/VNI) vào cửa sổ đang mở |
| Phím Esc | `KESC` | Bấm phím Esc |
| Phím Enter | `KENTER` | Bấm phím Enter |
| Phím Xóa lùi | `KBACKSPACE`| Bấm phím Backspace |
| Phím Tab | `KTAB` | Bấm phím Tab |
| Phím Windows | `KHOMESCREEN`| Mở menu Start của Windows (phím Win) |
| Đa nhiệm | `KRECENTS` | Mở Task View chuyển đổi cửa sổ (Win + Tab) |

*Lưu ý: Để điều khiển các cửa sổ đang chạy với quyền Quản trị viên (Administrator), bạn cần chạy `HCView` bằng quyền Administrator.*

---

## 6. Cấu trúc thư mục

```text
HCView/
├── main.py              # Toàn bộ ứng dụng gộp trong 1 file duy nhất
├── remote_config.json   # File lưu cấu hình (tọa độ vùng, cổng, server...)
└── requirements.txt     # Danh sách thư viện phụ thuộc (PyQt6, websockets, numpy)
```

---

## 🌐 Website & Tác giả

- **Trang chủ:** [https://hetcuu.com](https://hetcuu.com)

