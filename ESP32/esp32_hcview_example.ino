/**
 * ============================================================================
 * HCView — ESP32 Remote Client Example
 * ============================================================================
 * Ví dụ mẫu kết nối vi điều khiển ESP32 với máy chủ HCView (Windows / Android)
 * qua WebSocket để:
 *  1. Nhận luồng màn hình nhị phân 191x62 (1-bit / 1.488B hoặc 2-bit / 2.976B).
 *  2. Giải mã điểm ảnh hiển thị lên màn hình LCD / OLED hoặc Serial.
 *  3. Gửi lệnh điều khiển chuột, cảm ứng, phím bấm và gõ văn bản lên máy chủ.
 *
 * Thư viện phụ thuộc (cài đặt qua Arduino Library Manager):
 *  - WebSockets by Markus Sattler (v2.4.0+)
 *  - ArduinoJson by Benoit Blanchon (v6.x hoặc v7.x)
 * ============================================================================
 */

#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>

// --- CẤU HÌNH WIFI & MÁY CHỦ HCVIEW ---
const char* WIFI_SSID     = "YOUR_WIFI_SSID";     // Tên WiFi hoặc Hotspot điện thoại
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD"; // Mật khẩu WiFi

const char* HC_SERVER_IP  = "192.168.1.15";       // Địa chỉ IP hiển thị trên HCView
const uint16_t HC_PORT    = 8268;                 // Cổng WebSocket mặc định của HCView
const char* HC_WS_PATH    = "/";                  // Đường dẫn WebSocket (mặc định "/")

// --- ĐẶC TẢ KHUNG HÌNH LCD 191x62 ---
#define LCD_WIDTH          191
#define LCD_HEIGHT         62
#define FRAME_SIZE_1BIT    1488 // 1-bit monochrome (191x62 / 8 chẵn 1488B)
#define FRAME_SIZE_2BIT    2976 // 2-bit 4-level grayscale (191x62 * 2 / 8 = 2976B)

WebSocketsClient webSocket;
bool isConnected = false;
uint32_t lastFrameCount = 0;
uint32_t frameCounter = 0;
uint32_t lastFpsCheck = 0;

// Bộ đệm lưu trữ 1 khung hình nhị phân nhận được
uint8_t frameBuffer[FRAME_SIZE_2BIT];
size_t lastFrameSize = 0;

// ============================================================================
// HÀM GIẢI MÃ ĐIỂM ẢNH TỪ DỮ LIỆU NHỊ PHÂN
// ============================================================================

/**
 * Lấy giá trị pixel (x, y) từ khung hình 1-bit (1488 bytes).
 * Trả về: 0 = Điểm sáng (nền), 1 = Điểm tối (nét vẽ).
 */
uint8_t getPixel1Bit(const uint8_t* buffer, int x, int y) {
    if (x < 0 || x >= LCD_WIDTH || y < 0 || y >= LCD_HEIGHT) return 0;
    int bytesPerRow = 24; // (191 + 7) / 8 = 24 bytes
    int byteIdx = y * bytesPerRow + (x / 8);
    int bitPos = 7 - (x % 8); // MSB bên trái
    return (buffer[byteIdx] >> bitPos) & 0x01;
}

/**
 * Lấy mức xám pixel (x, y) từ khung hình 2-bit (2976 bytes).
 * Trả về: 0 (Trắng/Nền), 1 (Xám nhạt), 2 (Xám đậm), 3 (Đen tuyền).
 */
uint8_t getPixel2Bit(const uint8_t* buffer, int x, int y) {
    if (x < 0 || x >= LCD_WIDTH || y < 0 || y >= LCD_HEIGHT) return 0;
    int pixelIndex = y * LCD_WIDTH + x;
    int byteIndex = pixelIndex / 4;
    int shift = 6 - ((pixelIndex % 4) * 2); // 2 bit mỗi pixel, MSB bên trái
    return (buffer[byteIndex] >> shift) & 0x03;
}

/**
 * Hàm render hiển thị khung hình (người dùng tích hợp thư viện màn hình tại đây)
 */
void renderFrame(const uint8_t* data, size_t length) {
    frameCounter++;

    if (length == FRAME_SIZE_2BIT) {
        uint8_t centerPixel = getPixel2Bit(data, LCD_WIDTH / 2, LCD_HEIGHT / 2);
        (void)centerPixel; // Sử dụng centerPixel để vẽ ra LCD / OLED
    } else if (length == FRAME_SIZE_1BIT) {
        uint8_t centerPixel = getPixel1Bit(data, LCD_WIDTH / 2, LCD_HEIGHT / 2);
        (void)centerPixel;
    }
}


// ============================================================================
// HÀM GỬI LỆNH ĐIỀU KHIỂN LÊN MÁY CHỦ HCVIEW
// ============================================================================

void sendCommand(const String& cmd) {
    if (!isConnected) return;
    webSocket.sendTXT(cmd);
    Serial.printf("[CMD TX] %s\n", cmd.c_str());
}

// Click chuột trái / Chạm cảm ứng tại toạ độ (x, y) trên vùng 191x62
void sendTap(int x, int y) {
    sendCommand("C" + String(x) + "," + String(y));
}

// Click chuột phải / Nhấn giữ tại toạ độ (x, y)
void sendLongPress(int x, int y) {
    sendCommand("P" + String(x) + "," + String(y));
}

// Cuộn trang: delta dương = cuộn xuống, delta âm = cuộn lên
void sendScroll(int delta) {
    sendCommand("S" + String(delta));
}

// Đổi tốc độ cuộn: "0.5", "1.0", "1.5", "2.0" hoặc "+", "-"
void sendScrollSpeed(const String& speed) {
    sendCommand("V" + speed);
}

// Dời vùng chụp màn hình theo hướng: hx, hy thuộc {-1, 0, 1}
void sendPan(int hx, int hy) {
    sendCommand("M" + String(hx) + "," + String(hy));
}

// Thu phóng vùng chụp: "1.0", "1.5", "2.0", "3.0" hoặc "+", "-"
void sendZoom(const String& zoom) {
    sendCommand("Z" + zoom);
}

// Gõ chuỗi ký tự Unicode tiếng Việt có dấu
void sendText(const String& text) {
    sendCommand("T" + text);
}

// Gửi các phím chức năng: "ESC", "ENTER", "BACKSPACE", "TAB", "HOMESCREEN", "RECENTS"
void sendKey(const String& keyName) {
    sendCommand("K" + keyName);
}

// ============================================================================
// XỬ LÝ SỰ KIỆN WEBSOCKET
// ============================================================================

void webSocketEvent(WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_DISCONNECTED:
            isConnected = false;
            Serial.println("[WS] Da ngat ket noi toi HCView Server!");
            break;

        case WStype_CONNECTED:
            isConnected = true;
            Serial.printf("[WS] Ket noi thanh cong toi HCView Server: %s\n", payload);
            break;

        case WStype_BIN:
            // Nhận dữ liệu hình ảnh nhị phân từ HCView
            if (length == FRAME_SIZE_1BIT || length == FRAME_SIZE_2BIT) {
                memcpy(frameBuffer, payload, length);
                lastFrameSize = length;
                renderFrame(frameBuffer, length);
            }
            break;

        case WStype_TEXT:
            // Nhận tin nhắn JSON (ví dụ keepalive ping / thông báo)
            if (payload[0] == '{') {
                StaticJsonDocument<256> doc;
                DeserializationError err = deserializeJson(doc, payload, length);
                if (!err) {
                    const char* msgType = doc["type"];
                    if (msgType && strcmp(msgType, "ping") == 0) {
                        webSocket.sendTXT("{\"type\":\"pong\"}");
                    }
                }
            }
            break;

        case WStype_ERROR:
            Serial.println("[WS] Loi ket noi WebSocket!");
            break;

        default:
            break;
    }
}

// ============================================================================
// ARDUINO SETUP & LOOP
// ============================================================================

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n=== HCView ESP32 Client Khoi Dong ===");

    // Kết nối WiFi
    Serial.printf("Dang ket noi WiFi: %s\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED) {
        delay(300);
        Serial.print(".");
    }
    Serial.printf("\nWiFi da ket noi! IP ESP32: %s\n", WiFi.localIP().toString().c_str());

    // Cấu hình WebSocket Client kết nối đến HCView Server
    Serial.printf("Dang ket noi toi HCView Server %s:%d...\n", HC_SERVER_IP, HC_PORT);
    webSocket.begin(HC_SERVER_IP, HC_PORT, HC_WS_PATH);
    webSocket.onEvent(webSocketEvent);
    webSocket.setReconnectInterval(2000); // Tự động kết nối lại sau 2 giây nếu mất mạng
}

void loop() {
    webSocket.loop();

    // Thống kê FPS nhận được mỗi 2 giây
    uint32_t now = millis();
    if (now - lastFpsCheck >= 2000) {
        float fps = (frameCounter - lastFrameCount) * 1000.0f / (now - lastFpsCheck);
        lastFrameCount = frameCounter;
        lastFpsCheck = now;
        if (isConnected) {
            Serial.printf("[STATUS] Dang nhan: %.1f FPS | Khung gan nhat: %d bytes\n", fps, (int)lastFrameSize);
        }
    }

    // Đọc lệnh thử nghiệm từ Serial Monitor:
    // Gõ 'c' -> Click tâm màn hình
    // Gõ 's' -> Cuộn trang xuống
    // Gõ 't' -> Gõ dòng chữ mẫu
    // Gõ 'h' -> Phím Home
    if (Serial.available()) {
        char ch = Serial.read();
        if (ch == 'c') {
            sendTap(LCD_WIDTH / 2, LCD_HEIGHT / 2);
        } else if (ch == 's') {
            sendScroll(60);
        } else if (ch == 't') {
            sendText("Xin chao tu ESP32!");
        } else if (ch == 'h') {
            sendKey("HOMESCREEN");
        }
    }
}
