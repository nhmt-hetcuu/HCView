"""
main.py - HCView (Windows).

Chụp 1 VÙNG màn hình PC -> đóng gói 2-bit (2,976 byte, 4 mức xám)
-> gửi xuống thiết bị (nối thẳng hoặc qua Server Relay), và làm theo lệnh
chuột / phím gửi lên.
"""

import asyncio
import ctypes
from ctypes import wintypes
import json
import logging
import os
import socket
import sys
import threading
import time
from typing import Callable, Dict, List, Optional, Tuple
import urllib.error
import urllib.parse
import urllib.request

if sys.stdout.encoding != 'utf-8':
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except AttributeError:
        pass
if sys.stderr.encoding != 'utf-8':
    try:
        sys.stderr.reconfigure(encoding='utf-8')
    except AttributeError:
        pass

import numpy as np
from PyQt6.QtCore import Qt, QTimer, QRect, QPoint, QSize, pyqtSignal
from PyQt6.QtGui import (
    QGuiApplication, QImage, QPainter, QColor, QPen, QBrush, QFont, QCursor
)
from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QDialog, QWidget, QVBoxLayout, QHBoxLayout,
    QFormLayout, QGridLayout, QGroupBox, QLabel, QPushButton, QButtonGroup,
    QRadioButton, QComboBox, QSpinBox, QCheckBox, QTextEdit, QLineEdit,
    QFrame, QMessageBox
)
import websockets

logger = logging.getLogger("RemotePC")


# ============================================================
# 1. GIẢ LẬP CHUỘT / PHÍM WINDOWS (SendInput)
# ============================================================

_user32 = ctypes.WinDLL("user32", use_last_error=True)

INPUT_MOUSE = 0
INPUT_KEYBOARD = 1

MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

WHEEL_DELTA = 120

VK_TAB = 0x09
VK_RETURN = 0x0D
VK_LWIN = 0x5B

# Tên phím trong lệnh K<tên> -> mã phím ảo (VK)
PHIM_DON = {
    "ENTER": VK_RETURN,
    "ESC": 0x1B,
    "TAB": VK_TAB,
    "SPACE": 0x20,
    "BACKSPACE": 0x08,
    "UP": 0x26,
    "DOWN": 0x28,
    "LEFT": 0x25,
    "RIGHT": 0x27,
}

# Phím ĐIỀU HƯỚNG đặt tên theo ý định, dùng chung với app Android
# (REMOTE_PC_ANDROID.md): Win = mở Start, Win+Tab = Task View.
TO_HOP_PHIM = {
    "HOMESCREEN": (VK_LWIN,),
    "RECENTS": (VK_LWIN, VK_TAB),
}

_PHIM_MO_RONG = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2D, 0x2E, VK_LWIN, 0x5C, 0x5D}

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG),
        ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUT_U)]


_user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
_user32.SendInput.restype = wintypes.UINT
_user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
_user32.MapVirtualKeyW.restype = wintypes.UINT


def _chuot(co: int, du_lieu: int = 0) -> INPUT:
    i = INPUT(type=INPUT_MOUSE)
    i.mi.dwFlags = co
    i.mi.mouseData = du_lieu & 0xFFFFFFFF   # DWORD không dấu: lăn âm phải quấn lại
    return i


def _phim_vk(vk: int, nha: bool) -> INPUT:
    i = INPUT(type=INPUT_KEYBOARD)
    i.ki.wVk = vk
    i.ki.wScan = _user32.MapVirtualKeyW(vk, 0) & 0xFFFF   # MAPVK_VK_TO_VSC
    co = KEYEVENTF_KEYUP if nha else 0
    if vk in _PHIM_MO_RONG:
        co |= KEYEVENTF_EXTENDEDKEY
    i.ki.dwFlags = co
    return i


def _phim_unicode(don_vi: int, nha: bool) -> INPUT:
    i = INPUT(type=INPUT_KEYBOARD)
    i.ki.wScan = don_vi
    i.ki.dwFlags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if nha else 0)
    return i


def _gui(ds: list) -> int:
    """Gửi cả danh sách sự kiện trong 1 lần gọi. Trả về số sự kiện Windows nhận."""
    if not ds:
        return 0
    mang = (INPUT * len(ds))(*ds)
    return _user32.SendInput(len(ds), mang, ctypes.sizeof(INPUT))


def bam_chuot(phai: bool = False) -> int:
    """Bấm + nhả chuột (trái hoặc phải) tại chỗ con trỏ."""
    if phai:
        return _gui([_chuot(MOUSEEVENTF_RIGHTDOWN), _chuot(MOUSEEVENTF_RIGHTUP)])
    return _gui([_chuot(MOUSEEVENTF_LEFTDOWN), _chuot(MOUSEEVENTF_LEFTUP)])


def lan_chuot(gia_tri: int) -> int:
    """Lăn chuột: gia_tri > 0 = lăn TỚI (trang đi lên), < 0 = lăn LUI (trang
    đi xuống). WHEEL_DELTA (120) = đúng 1 nấc."""
    if gia_tri == 0:
        return 0
    return _gui([_chuot(MOUSEEVENTF_WHEEL, gia_tri)])


def bam_phim(ten: str) -> bool:
    """Phím K<tên>. False nếu tên lạ (không gửi gì)."""
    ten = ten.strip().upper()
    to_hop = TO_HOP_PHIM.get(ten)
    if to_hop is None:
        vk = PHIM_DON.get(ten)
        if vk is None:
            return False
        to_hop = (vk,)
    # Nhấn theo thứ tự, nhả ngược lại
    _gui([_phim_vk(vk, False) for vk in to_hop] + [_phim_vk(vk, True) for vk in reversed(to_hop)])
    return True


def go_chu(chuoi: str) -> int:
    """Gõ chuỗi Unicode vào cửa sổ đang active. Xuống dòng -> Enter, Tab -> Tab."""
    ds = []
    for kt in chuoi:
        if kt == "\r":
            continue
        if kt in ("\n", "\t"):
            vk = VK_RETURN if kt == "\n" else VK_TAB
            ds += [_phim_vk(vk, False), _phim_vk(vk, True)]
            continue
        b = kt.encode("utf-16-le")
        for k in range(0, len(b), 2):
            don_vi = b[k] | (b[k + 1] << 8)
            ds += [_phim_unicode(don_vi, False), _phim_unicode(don_vi, True)]
    return _gui(ds)



class dk:
    bam_chuot = staticmethod(bam_chuot)
    lan_chuot = staticmethod(lan_chuot)
    bam_phim = staticmethod(bam_phim)
    go_chu = staticmethod(go_chu)


# ============================================================
# 2. ĐÓNG GÓI KHUNG HÌNH (ST75256 191x62)
# ============================================================

RONG_MAN = 191
CAO_MAN = 62
RONG_MAN_BIT = 192

SO_BYTE_HANG_2BIT = (RONG_MAN_BIT * 2) // 8        # 48
SO_BYTE_KHUNG_2BIT = SO_BYTE_HANG_2BIT * CAO_MAN   # 2976

MUC_NET_TAT, MUC_NET_CAN_BANG, MUC_NET_CAO = 0, 1, 2


def _tao_bang_tuong_phan(muc: int) -> np.ndarray:
    """Đường cong tương phản chuẩn Android (processLumaBuffer), dạng bảng 256 mức."""
    bang = np.arange(256, dtype=np.int32)
    if muc >= MUC_NET_CAO:
        bang = np.select([bang < 90, bang < 140, bang < 185], [0, 60, 130], 255)
    elif muc == MUC_NET_CAN_BANG:
        bang = np.select(
            [bang < 75, bang < 165, bang > 210],
            [(bang * 0.6).astype(np.int32), (bang * 0.85).astype(np.int32),
             np.minimum(255, (210 + (bang - 210) * 1.5).astype(np.int32))],
            bang,
        )
    return bang.astype(np.uint8)


BANG_TUONG_PHAN = {m: _tao_bang_tuong_phan(m) for m in (MUC_NET_CAN_BANG, MUC_NET_CAO)}


def convert_image_to_target(qimage: QImage) -> QImage:
    """Thu nhỏ ảnh về 191 x 62, dạng Grayscale8."""
    if qimage.isNull():
        img = QImage(RONG_MAN, CAO_MAN, QImage.Format.Format_Grayscale8)
        img.fill(255)
        return img

    if qimage.height() == CAO_MAN and qimage.width() in (RONG_MAN, RONG_MAN_BIT):
        img = qimage.copy(0, 0, RONG_MAN, CAO_MAN)
    else:
        img = qimage.scaled(
            RONG_MAN, CAO_MAN,
            Qt.AspectRatioMode.IgnoreAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
    if img.format() != QImage.Format.Format_Grayscale8:
        img = img.convertToFormat(QImage.Format.Format_Grayscale8)
    return img


def anh_sang(qimage: QImage) -> np.ndarray:
    """Ảnh bất kỳ -> mảng độ sáng 62 x 191 (uint8, 0 tối .. 255 sáng)."""
    img = convert_image_to_target(qimage)
    con_tro = img.constBits()
    con_tro.setsize(img.sizeInBytes())
    mang = np.frombuffer(bytes(con_tro), dtype=np.uint8)
    return mang.reshape(CAO_MAN, img.bytesPerLine())[:, :RONG_MAN].copy()


def tang_net(sang: np.ndarray, muc_net: int) -> np.ndarray:
    """Làm sắc cạnh Unsharp Mask 3x3 rồi kéo tương phản (chuẩn Android)."""
    if muc_net <= MUC_NET_TAT:
        return sang
    x = sang.astype(np.int16)
    ra = x.copy()
    ra[1:-1, 1:-1] = (5 * x[1:-1, 1:-1] - x[1:-1, :-2] - x[1:-1, 2:]
                      - x[:-2, 1:-1] - x[2:, 1:-1])
    np.clip(ra, 0, 255, out=ra)
    return BANG_TUONG_PHAN[min(muc_net, MUC_NET_CAO)][ra.astype(np.uint8)]


def _dong_goi_2bit(gia_tri: np.ndarray) -> bytes:
    """gia_tri: mảng 62 x 191, mỗi điểm 0..3 (00 trắng .. 11 đen)."""
    day_du = np.zeros((CAO_MAN, RONG_MAN_BIT), dtype=np.uint8)
    day_du[:, :RONG_MAN] = gia_tri
    nhom = day_du.reshape(CAO_MAN, SO_BYTE_HANG_2BIT, 4)
    goi = (nhom[:, :, 0] << 6) | (nhom[:, :, 1] << 4) | (nhom[:, :, 2] << 2) | nhom[:, :, 3]
    return goi.astype(np.uint8).tobytes()


def pack_frame(
    qimage: QImage,
    invert: bool = False,
    muc_net: int = MUC_NET_CAN_BANG,
    **_kwargs,
) -> bytes:
    """Đóng gói khung 2976 byte (2-bit, 4 mức xám)."""
    sang = anh_sang(qimage)
    sang = tang_net(sang, muc_net)
    muc = sang >> 6
    return _dong_goi_2bit(muc if invert else 3 - muc)


def giai_ma_muc(du_lieu: bytes) -> np.ndarray:
    """Khung 2976 byte (2-bit) -> mảng 62 x 191 giá trị 0 (trắng) .. 3 (đen). Độ dài sai -> toàn 0."""
    if len(du_lieu) == SO_BYTE_KHUNG_2BIT:
        b = np.frombuffer(du_lieu, dtype=np.uint8).reshape(CAO_MAN, SO_BYTE_HANG_2BIT)
        muc = np.stack([(b >> 6) & 3, (b >> 4) & 3, (b >> 2) & 3, b & 3], axis=2)
        return muc.reshape(CAO_MAN, RONG_MAN_BIT)[:, :RONG_MAN].astype(np.uint8)
    return np.zeros((CAO_MAN, RONG_MAN), dtype=np.uint8)



_THU_MUC_HIEN_TAI = os.path.dirname(os.path.abspath(__file__))
FILE_CONFIG = os.path.join(_THU_MUC_HIEN_TAI, "remote_config.json")


def load_config() -> dict:
    default_cfg = {
        "che_do": "direct_server",
        "direct_port": 8268,
        "server_url": "http://localhost:8000",
        "ws_path": "/ws/remote",
        "username": "",
        "password": "",
        "device_id": "",
        "token": "",
        "fps": 5,
        "region_x": 817,
        "region_y": 493,
        "region_w": 286,
        "region_h": 93,
        "region_zoom": 1.5,
        "pan_ty_le": 0.5,
        "toc_do_cuon": 1.0,
        "muc_net": 1,
        "invert": False,
    }
    if os.path.isfile(FILE_CONFIG):
        try:
            with open(FILE_CONFIG, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    for k in default_cfg:
                        if k in data:
                            default_cfg[k] = data[k]
        except Exception:
            pass
    return default_cfg


def save_config(cfg: dict) -> bool:
    try:
        with open(FILE_CONFIG, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2, ensure_ascii=False)
        return True
    except Exception:
        return False


_doc_cau_hinh_remote = load_config
_ghi_cau_hinh_remote = save_config


# ============================================================
# 3. DIRECT WEBSOCKET SERVER (Cổng 8268)
# ============================================================

GIU_SONG_GIAY = 2.0          # gửi lại khung dù không đổi, tránh mất hình
GIOI_HAN_BO_DEM_GUI = 4096   # > ~1 khung đang chờ trong bộ đệm thì send() chờ -> khung mới đè khung cũ
BO_DEM_SOCKET_GUI = 8192     # bộ đệm gửi của hệ điều hành (Windows tự nới rộng -> hàng chục khung nằm chờ)

TIN_GHEP_CAP = json.dumps({"type": "da_ghep_cap"})
TIN_BAN_KIA_NGAT = json.dumps({"type": "ban_kia_ngat"})


def lay_ip_lan() -> str:
    """IPv4 chính của máy (card mạng đang ra Internet)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def lay_ds_ip_lan() -> List[str]:
    """Mọi IPv4 không phải loopback, IP chính đứng đầu. Thiết bị bắt Hotspot của
    Windows thì phải nhập IP card Hotspot (thường 192.168.137.1) chứ không
    phải IP chính - giống app Android hiện hết IP cho người dùng chọn."""
    ds = []
    chinh = lay_ip_lan()
    if not chinh.startswith("127."):
        ds.append(chinh)
    try:
        for thong_tin in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = thong_tin[4][0]
            if not ip.startswith("127.") and ip not in ds:
                ds.append(ip)
    except Exception:
        pass
    return ds or ["127.0.0.1"]


def gioi_han_bo_dem_socket(ws):
    """Thu nhỏ bộ đệm gửi TCP của 1 kết nối: mạng chậm thì khung dồn ở ô khung
    mới nhất (bị đè) chứ không nằm chờ trong hệ điều hành -> hình không trễ dồn."""
    try:
        sock = ws.transport.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, BO_DEM_SOCKET_GUI)
    except Exception:
        pass


class _ThietBi:
    """1 thiết bị đang nối: trạng thái gửi riêng của nó."""

    def __init__(self, ws, ip: str):
        self.ws = ws
        self.ip = ip
        self.co_viec = asyncio.Event()     # có khung mới / đổi trạng thái chia sẻ
        self.da_bao_chia_se = False        # đã gửi da_ghep_cap (True) hay chưa/ban_kia_ngat (False)
        self.khung_da_gui: Optional[bytes] = None
        self.luc_gui = 0.0


class DirectServer:
    """
    Máy chủ WebSocket trực tiếp. Giao diện dùng chung với RelayClient:
      - bat_dau() / dung()
      - dat_chia_se(bat)        bắt đầu / dừng chia sẻ màn hình
      - gui_khung(buffer)       khung mới nhất (gọi mỗi nhịp chụp, tự lọc trùng)
      - lay_thong_ke() -> dict
      - khi_co_lenh(lenh), khi_doi_trang_thai(ma, mo_ta)
    """

    def __init__(
        self,
        port: int = 8268,
        ws_path: str = "/ws/remote",
        khi_co_lenh: Optional[Callable[[str], None]] = None,
        khi_doi_trang_thai: Optional[Callable[[str, str], None]] = None,
    ):
        self.port = port
        self.ws_path = ws_path if ws_path.startswith("/") else f"/{ws_path}"
        self.khi_co_lenh = khi_co_lenh
        self.khi_doi_trang_thai = khi_doi_trang_thai

        self._may: Dict[object, _ThietBi] = {}
        self._server = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._thread: Optional[threading.Thread] = None
        self._is_running = False
        self._dang_chia_se = False
        self._khung_moi_nhat: Optional[bytes] = None
        self._ip_lan = lay_ip_lan()

        self.so_khung_da_gui = 0
        self.so_byte_da_gui = 0

    # ================= GIAO DIỆN CÔNG KHAI =================

    def lay_ip_lan(self) -> str:
        self._ip_lan = lay_ip_lan()
        return self._ip_lan

    def lay_ws_url(self) -> str:
        return f"ws://{self.lay_ip_lan()}:{self.port}{self.ws_path}"

    def so_client_dang_ket_noi(self) -> int:
        return len(self._may)

    def ds_ip_thiet_bi(self) -> List[str]:
        return sorted({m.ip for m in list(self._may.values())})

    def da_ket_noi(self) -> bool:
        return self._is_running

    def lay_thong_ke(self) -> dict:
        return {
            "so_khung_da_gui": self.so_khung_da_gui,
            "so_byte_da_gui": self.so_byte_da_gui,
            "ds_thiet_bi": self.ds_ip_thiet_bi(),
        }

    def bat_dau(self):
        """Mở WebSocket Server ở luồng nền."""
        if self._is_running:
            return
        self._is_running = True
        self._thread = threading.Thread(target=self._run_server_thread, daemon=True, name="DirectServerThread")
        self._thread.start()

    def dung(self):
        """Đóng server và ngắt mọi kết nối."""
        if not self._is_running:
            return
        self._is_running = False
        if self._loop and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._stop_server_coroutine)
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)
        self._thread = None
        self._thong_bao_trang_thai("da_ngat", "Server đã dừng")

    def dat_chia_se(self, bat: bool):
        """Bật: mọi thiết bị đang nối nhận da_ghep_cap + khung hiện tại ngay.
        Tắt: gửi ban_kia_ngat, giữ kết nối."""
        if self._dang_chia_se == bat:
            return
        self._dang_chia_se = bat
        self._danh_thuc_tat_ca_threadsafe()
        self._cap_nhat_trang_thai_threadsafe()

    def gui_khung(self, buffer: bytes):
        """Đặt khung mới nhất (1488 / 2976 byte). Gửi đi hay không do từng
        Tự quyết (lọc trùng + giữ sống 2 giây)."""
        self._khung_moi_nhat = bytes(buffer)
        if self._may:
            self._danh_thuc_tat_ca_threadsafe()

    # ================= NỘI BỘ =================

    def _thong_bao_trang_thai(self, trang_thai: str, mo_ta: str):
        if self.khi_doi_trang_thai:
            try:
                self.khi_doi_trang_thai(trang_thai, mo_ta)
            except Exception as e:
                logger.warning(f"Lỗi callback trạng thái: {e}")

    def _danh_thuc_tat_ca_threadsafe(self):
        loop = self._loop
        if loop and loop.is_running():
            try:
                loop.call_soon_threadsafe(self._danh_thuc_tat_ca)
            except RuntimeError:
                pass

    def _danh_thuc_tat_ca(self):
        for may in self._may.values():
            may.co_viec.set()

    def _cap_nhat_trang_thai_threadsafe(self):
        loop = self._loop
        if loop and loop.is_running():
            try:
                loop.call_soon_threadsafe(self._cap_nhat_trang_thai)
            except RuntimeError:
                pass

    def _cap_nhat_trang_thai(self):
        ds = self.ds_ip_thiet_bi()
        if not ds:
            self._thong_bao_trang_thai("cho_thiet_bi", "Chờ thiết bị...")
        elif self._dang_chia_se:
            self._thong_bao_trang_thai("da_ghep_cap", "Đang chia sẻ")
        else:
            self._thong_bao_trang_thai("da_noi", "Đã kết nối")

    def _run_server_thread(self):
        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(self._main_server_task())
        except Exception as e:
            logger.error(f"Lỗi chạy DirectServer: {e}")
            self._is_running = False
            self._thong_bao_trang_thai("loi", f"Lỗi mở Server: {e}")
        finally:
            try:
                pending = asyncio.all_tasks(loop)
                for t in pending:
                    t.cancel()
                if pending:
                    loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            except Exception:
                pass
            loop.close()
            if self._loop is loop:          # bat_dau() mới có thể đã tạo vòng lặp khác
                self._loop = None

    async def _main_server_task(self):
        try:
            self._server = await websockets.serve(
                self._client_handler,
                "0.0.0.0",
                self.port,
                ping_interval=25,
                ping_timeout=20,
                max_size=4096,
                write_limit=GIOI_HAN_BO_DEM_GUI,
            )
            logger.info(f"[DirectServer] Đang lắng nghe tại {self.lay_ws_url()}")
            self._cap_nhat_trang_thai()
            await self._server.wait_closed()
        except OSError as e:
            logger.error(f"Không thể mở cổng {self.port}: {e}")
            self._is_running = False        # bat_dau() lần sau thử mở lại
            self._thong_bao_trang_thai("loi", f"Cổng {self.port} đang bận hoặc bị chặn")
        except asyncio.CancelledError:
            pass

    def _stop_server_coroutine(self):
        asyncio.create_task(self._shutdown())

    async def _shutdown(self):
        if self._server:
            self._server.close()
            await self._server.wait_closed()
        for may in list(self._may.values()):
            try:
                await may.ws.close()
            except Exception:
                pass
        self._may.clear()

    async def _vong_gui(self, may: _ThietBi):
        """Luồng GỬI duy nhất của 1 thiết bị: tin ghép cặp luôn đi TRƯỚC khung ảnh,
        và chỉ khung mới nhất được gửi (khung cũ bị đè khi send() còn đang chờ)."""
        try:
            await self._vong_gui_chinh(may)
        except websockets.ConnectionClosed:
            pass
        except Exception as e:
            logger.warning(f"[DirectServer] Lỗi gửi tới {may.ip}: {e}")

    async def _vong_gui_chinh(self, may: _ThietBi):
        while True:
            try:
                await asyncio.wait_for(may.co_viec.wait(), timeout=GIU_SONG_GIAY)
            except asyncio.TimeoutError:
                pass
            may.co_viec.clear()

            chia_se = self._dang_chia_se
            if chia_se != may.da_bao_chia_se:
                await may.ws.send(TIN_GHEP_CAP if chia_se else TIN_BAN_KIA_NGAT)
                may.da_bao_chia_se = chia_se
                may.khung_da_gui = None          # vừa ghép cặp: gửi khung bỏ qua lọc trùng
            if not chia_se:
                continue

            khung = self._khung_moi_nhat
            if khung is None:
                continue
            bay_gio = time.monotonic()
            if khung == may.khung_da_gui and bay_gio - may.luc_gui < GIU_SONG_GIAY:
                continue
            await may.ws.send(khung)
            may.khung_da_gui = khung
            may.luc_gui = bay_gio
            self.so_khung_da_gui += 1
            self.so_byte_da_gui += len(khung)

    async def _client_handler(self, websocket):
        """Mỗi thiết bị nối vào: 1 vòng nhận lệnh + 1 vòng gửi."""
        ip = websocket.remote_address[0] if websocket.remote_address else "Client"
        may = _ThietBi(websocket, ip)
        gioi_han_bo_dem_socket(websocket)
        self._may[websocket] = may
        logger.info(f"[DirectServer] Thiết bị nối từ {ip}")
        self._cap_nhat_trang_thai()

        may.co_viec.set()   # đang chia sẻ thì gửi da_ghep_cap + khung ngay
        task_gui = asyncio.create_task(self._vong_gui(may))
        try:
            async for message in websocket:
                if not isinstance(message, str):
                    continue                     # bỏ qua nhị phân
                lenh = message.strip()
                if not lenh:
                    continue
                if lenh.startswith("{"):
                    # JSON điều phối, không phải lệnh
                    try:
                        if json.loads(lenh).get("type") == "ping":
                            await websocket.send(json.dumps({"type": "pong"}))
                    except Exception:
                        pass
                    continue
                if self.khi_co_lenh:
                    try:
                        self.khi_co_lenh(lenh)   # chỉ đẩy sang luồng Qt, không chặn vòng nhận
                    except Exception as e:
                        logger.warning(f"Lỗi khi xử lý lệnh: {e}")
        except websockets.ConnectionClosed:
            pass
        finally:
            task_gui.cancel()
            self._may.pop(websocket, None)
            logger.info(f"[DirectServer] Thiết bị {ip} đã ngắt")
            self._cap_nhat_trang_thai()

# ============================================================
# 4. RELAY CLIENT (/ws/remote)
# ============================================================

FRAME_WIDTH = 192
FRAME_HEIGHT = 62
FORCE_RESEND_INTERVAL = 2.0                    # giây: gửi lại khung dù không đổi để tránh mất hình
PING_INTERVAL = 25.0                           # giây: giữ websocket sống
GIOI_HAN_BO_DEM_GUI = 4096                     # > ~1 khung trong bộ đệm thì send() chờ -> khung mới đè khung cũ
SERVER_MSG_TYPES = {"cho_ban_kia", "da_ghep_cap", "ban_kia_ngat", "bi_thay_the", "error", "pong"}


class LoiXacThuc(Exception):
    """Server trả lời rõ ràng là sai tài khoản / không có quyền (không phải lỗi mạng)."""


def _chuan_hoa_http(server_url: str) -> str:
    base = (server_url or "http://localhost:8000").strip().rstrip("/")
    if base.startswith("ws://"):
        base = "http://" + base[5:]
    elif base.startswith("wss://"):
        base = "https://" + base[6:]
    elif not base.startswith(("http://", "https://")):
        base = "http://" + base
    return base


def build_ws_url(
    server_url: str,
    device_id: str,
    api_key: str = "",
    token: str = "",
    vai_tro: str = "pc",
    ws_path: str = "/ws/remote",
) -> str:
    """Chuẩn hóa server_url (http/https/ws/wss) thành WebSocket URL đầy đủ tới ws_path."""
    url = (server_url or "http://localhost:8000").strip()
    if url.startswith("https://"):
        url = "wss://" + url[len("https://"):]
    elif url.startswith("http://"):
        url = "ws://" + url[len("http://"):]
    elif not url.startswith(("ws://", "wss://")):
        url = "ws://" + url

    url = url.rstrip("/")
    path = ws_path if ws_path.startswith("/") else ("/" + ws_path)
    if not url.endswith(path):
        url = url + path

    params = {
        "device_id": (device_id or "").strip(),
        "vai_tro": vai_tro,
    }
    if token:
        params["token"] = token.strip()
    elif api_key:
        params["api_key"] = api_key.strip()

    qs = urllib.parse.urlencode(params)
    return f"{url}?{qs}"


def dang_nhap_server(server_url: str, username: str, password: str) -> str:
    """Đăng nhập lấy access_token. Sai tài khoản -> LoiXacThuc; lỗi mạng -> Exception."""
    base = _chuan_hoa_http(server_url)
    payload = json.dumps({"username": username.strip(), "password": password}).encode("utf-8")
    loi_cuoi = "server không phản hồi"
    for endpoint in ["/api/v1/auth/login", "/auth/login"]:
        try:
            req = urllib.request.Request(
                f"{base}{endpoint}",
                data=payload,
                headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=5) as res:
                data = json.loads(res.read().decode("utf-8"))
                token = data.get("access_token", "")
                if token:
                    return token
                loi_cuoi = "server không trả token"
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 403, 422):
                raise LoiXacThuc("Sai tên đăng nhập hoặc mật khẩu.")
            loi_cuoi = f"HTTP {e.code}"            # 404/405: thử đường dẫn dự phòng
        except Exception as e:
            loi_cuoi = str(e)
    raise Exception(f"Không đăng nhập được ({loi_cuoi}).")


def lay_danh_sach_thiet_bi(server_url: str, token: str) -> list:
    """Lấy danh sách thiết bị có quyền truy cập."""
    base = _chuan_hoa_http(server_url)
    for endpoint in ["/api/v1/devices", "/devices"]:
        try:
            req = urllib.request.Request(
                f"{base}{endpoint}",
                headers={"Authorization": f"Bearer {token}"}
            )
            with urllib.request.urlopen(req, timeout=5) as res:
                data = json.loads(res.read().decode("utf-8"))
                if isinstance(data, list):
                    return data
        except Exception:
            continue
    return []


def _ma_http_tu_choi(exc: Exception):
    """Mã HTTP khi server từ chối bắt tay - websockets cũ (.status_code) lẫn mới (.response)."""
    ma = getattr(exc, "status_code", None)
    if ma is None:
        ma = getattr(getattr(exc, "response", None), "status_code", None)
    return ma


class RelayClient:
    """Kết nối WebSocket 2 chiều giữa app PC và Server Relay (/ws/remote).
    Mỗi lần chia sẻ main.py tạo 1 đối tượng mới: bat_dau() ... dung()."""

    def __init__(
        self,
        server_url: str = "http://localhost:8000",
        device_id: str = "esp32-01",
        api_key: str = "",
        username: str = "",
        password: str = "",
        token: str = "",
        ws_path: str = "/ws/remote",
        khi_co_lenh=None,
        khi_doi_trang_thai=None,
        khi_co_token_moi=None,
    ):
        self.server_url = server_url
        self.device_id = device_id
        self.api_key = api_key
        self.username = username
        self.password = password
        self.token = token
        self.ws_path = ws_path
        self.khi_co_lenh = khi_co_lenh
        self.khi_doi_trang_thai = khi_doi_trang_thai
        self.khi_co_token_moi = khi_co_token_moi

        self._dang_chay = False
        self._da_ket_noi_ws = False
        self._da_ghep_cap = False
        self._thread = None
        self._loop = None
        self._task_chinh = None
        self._ws = None
        self._queue_khung = None
        self._khung_moi_nhat = None
        self._vua_ghep_cap = False      # vòng gửi bỏ qua lọc trùng cho khung kế tiếp

        # Thống kê hoạt động thời gian thực
        self.so_khung_da_gui = 0
        self.so_byte_da_gui = 0
        self.so_lenh_da_nhan = 0
        self.lenh_gan_nhat = ""
        self.ma_trang_thai = "chua_ket_noi"
        self.mo_ta_trang_thai = "Chưa kết nối"

    def da_ket_noi(self) -> bool:
        return self._da_ket_noi_ws and self._dang_chay

    def da_ghep_cap(self) -> bool:
        return self._da_ghep_cap and self.da_ket_noi()

    def lay_thong_ke(self) -> dict:
        return {
            "server_url": self.server_url,
            "device_id": self.device_id,
            "da_ket_noi": self.da_ket_noi(),
            "da_ghep_cap": self.da_ghep_cap(),
            "ma_trang_thai": self.ma_trang_thai,
            "mo_ta_trang_thai": self.mo_ta_trang_thai,
            "so_khung_da_gui": self.so_khung_da_gui,
            "so_byte_da_gui": self.so_byte_da_gui,
            "so_lenh_da_nhan": self.so_lenh_da_nhan,
            "lenh_gan_nhat": self.lenh_gan_nhat,
            "ds_thiet_bi": [self.device_id] if self.da_ghep_cap() else [],
        }

    def bat_dau(self):
        if self._dang_chay:
            return
        self._dang_chay = True
        self._thread = threading.Thread(target=self._chay_thread, daemon=True, name="RemoteRelayClientThread")
        self._thread.start()
        print(f"[Remote] Bắt đầu kết nối Server: {self.server_url} (Device: {self.device_id}, Path: {self.ws_path})")

    def dung(self):
        """Rời server (server tự báo ban_kia_ngat). Không chặn giao diện lâu."""
        self._dang_chay = False
        self._da_ket_noi_ws = False
        self._da_ghep_cap = False
        loop, task = self._loop, self._task_chinh
        if loop and task and loop.is_running():
            loop.call_soon_threadsafe(task.cancel)
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=0.5)
        print("[Remote] Đã dừng RemoteRelayClient.")

    def gui_khung(self, buffer: bytes):
        """Đặt khung mới nhất. Chỉ đẩy đi khi đã ghép cặp; lọc trùng ở vòng gửi."""
        self._khung_moi_nhat = bytes(buffer)
        if not self._dang_chay or not self._da_ghep_cap:
            return
        self._day_khung_threadsafe()

    def _day_khung_threadsafe(self):
        loop = self._loop
        if loop and self._queue_khung is not None:
            try:
                loop.call_soon_threadsafe(self._dat_khung_vao_hang_doi, self._khung_moi_nhat)
            except RuntimeError:
                pass

    def _dat_khung_vao_hang_doi(self, buffer):
        """Hàng đợi 1 khung: khung cũ chưa kịp gửi bị thay bằng khung mới."""
        if self._queue_khung is None or buffer is None:
            return
        if self._queue_khung.full():
            try:
                self._queue_khung.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            self._queue_khung.put_nowait(buffer)
        except asyncio.QueueFull:
            pass

    def _thong_bao(self, ma: str, mo_ta: str):
        self.ma_trang_thai = ma
        self.mo_ta_trang_thai = mo_ta
        if self.khi_doi_trang_thai:
            try:
                self.khi_doi_trang_thai(ma, mo_ta)
            except Exception as e:
                logger.warning(f"Lỗi callback trạng thái: {e}")

    def _dung_han(self, ma: str, mo_ta: str):
        """Dừng không nối lại nữa (bị thay thế, bị khoá, sai tài khoản)."""
        self._dang_chay = False
        self._thong_bao(ma, mo_ta)
        print(f"[Remote] Dừng hẳn: {mo_ta}")

    def _chay_thread(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._task_chinh = self._loop.create_task(self._vong_lap_chinh())
            self._loop.run_until_complete(self._task_chinh)
        except asyncio.CancelledError:
            pass
        finally:
            try:
                pending = asyncio.all_tasks(self._loop)
                for t in pending:
                    t.cancel()
                if pending:
                    self._loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            except Exception:
                pass
            self._loop.close()
            self._loop = None

    async def _dang_nhap(self):
        """Lấy token mới bằng tài khoản + mật khẩu (sai tài khoản -> LoiXacThuc)."""
        self._thong_bao("dang_dang_nhap", f"Đang đăng nhập tài khoản '{self.username}'...")
        loop = asyncio.get_running_loop()
        self.token = await loop.run_in_executor(
            None, dang_nhap_server, self.server_url, self.username, self.password
        )
        if self.khi_co_token_moi:
            try:
                self.khi_co_token_moi(self.token)
            except Exception:
                pass

    async def _vong_lap_chinh(self):
        self._queue_khung = asyncio.Queue(maxsize=1)
        backoff = 1.0
        da_dang_nhap_lai = False
        co_tai_khoan = bool(self.username and self.password)

        while self._dang_chay:
            if co_tai_khoan and not self.token:
                try:
                    await self._dang_nhap()
                except LoiXacThuc as e:
                    self._dung_han("loi", f"Đăng nhập thất bại: {e}")
                    return
                except Exception as e:
                    self._thong_bao("mat_ket_noi", f"Đăng nhập lỗi: {e} Thử lại sau {int(backoff)}s...")
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 1.5, 10.0)
                    continue

            ws_url = build_ws_url(
                self.server_url,
                self.device_id,
                api_key=self.api_key,
                token=self.token,
                vai_tro="pc",
                ws_path=self.ws_path,
            )
            self._thong_bao("dang_ket_noi", f"Đang kết nối Server {self.server_url} ({self.device_id})...")

            try:
                async with websockets.connect(
                    ws_url,
                    ping_interval=None,
                    close_timeout=5.0,
                    max_size=4096,
                    write_limit=GIOI_HAN_BO_DEM_GUI,
                ) as ws:
                    self._ws = ws
                    gioi_han_bo_dem_socket(ws)
                    self._da_ket_noi_ws = True
                    backoff = 1.0
                    da_dang_nhap_lai = False
                    self._thong_bao("cho_thiet_bi", "Đã vào Server, chờ thiết bị...")
                    print(f"[Remote] Đã kết nối thành công tới {self.server_url}")

                    cac_task = [
                        asyncio.create_task(self._vong_lap_nhan(ws)),
                        asyncio.create_task(self._vong_lap_gui(ws)),
                        asyncio.create_task(self._vong_lap_ping(ws)),
                    ]
                    try:
                        xong, _ = await asyncio.wait(cac_task, return_when=asyncio.FIRST_COMPLETED)
                    finally:
                        for t in cac_task:
                            t.cancel()
                    for t in xong:
                        loi = None if t.cancelled() else t.exception()
                        if loi and not isinstance(loi, websockets.ConnectionClosed):
                            print(f"[Remote] Lỗi kết nối: {loi}")

            except asyncio.CancelledError:
                raise
            except websockets.exceptions.InvalidHandshake as e:
                ma = _ma_http_tu_choi(e)
                if ma in (401, 403):
                    if co_tai_khoan and not da_dang_nhap_lai:
                        # Token hết hạn: đăng nhập lại đúng 1 lần rồi thử ngay.
                        da_dang_nhap_lai = True
                        self.token = ""
                        print(f"[Remote] Server từ chối (HTTP {ma}), đăng nhập lại...")
                        continue
                    self._dung_han(
                        "loi",
                        f"Server từ chối (HTTP {ma}): kiểm tra tài khoản (cần VIP 2/admin), thiết bị hoặc khoá PC.",
                    )
                else:
                    self._thong_bao("mat_ket_noi", f"Server từ chối kết nối ({e}). Thử lại sau {int(backoff)}s...")
            except Exception as exc:
                self._thong_bao("mat_ket_noi", f"Mất kết nối: {exc}. Thử lại sau {int(backoff)}s...")
                print(f"[Remote] Lỗi kết nối WebSocket: {exc}. Thử lại sau {backoff}s...")

            self._da_ket_noi_ws = False
            self._da_ghep_cap = False
            self._ws = None

            if not self._dang_chay:
                break

            await asyncio.sleep(backoff)
            backoff = min(backoff * 1.5, 10.0)

    async def _vong_lap_nhan(self, ws):
        async for raw in ws:
            if isinstance(raw, (bytes, bytearray)):
                continue
            lenh = raw.strip()
            if not lenh:
                continue

            if not lenh.startswith("{"):
                # Lệnh điều khiển chuyển nguyên văn (C20,30, S60, Ttext, KENTER...)
                self.so_lenh_da_nhan += 1
                self.lenh_gan_nhat = lenh
                if self.khi_co_lenh:
                    try:
                        self.khi_co_lenh(lenh)
                    except Exception as exc:
                        print(f"[Remote] Lỗi khi xử lý lệnh {lenh!r}: {exc}")
                continue

            try:
                data = json.loads(lenh)
            except json.JSONDecodeError:
                continue
            if not isinstance(data, dict) or data.get("type") not in SERVER_MSG_TYPES:
                continue

            msg_type = data["type"]
            if msg_type == "cho_ban_kia":
                self._da_ghep_cap = False
                self._thong_bao("cho_thiet_bi", "Đã vào Server, chờ thiết bị nối...")
            elif msg_type == "da_ghep_cap":
                self._da_ghep_cap = True
                self._thong_bao("da_ghep_cap", f"Đang chia sẻ ({self.device_id})")
                print("[Remote] Đã ghép cặp, gửi khung ngay.")
                self._vua_ghep_cap = True
                self._dat_khung_vao_hang_doi(self._khung_moi_nhat)
            elif msg_type == "ban_kia_ngat":
                self._da_ghep_cap = False
                self._thong_bao("thiet_bi_ngat", "Thiết bị vừa ngắt. Chờ nối lại...")
            elif msg_type == "bi_thay_the":
                self._da_ghep_cap = False
                self._dung_han("loi", "Một máy PC khác vừa chiếm thiết bị này - đã dừng (không tự nối lại).")
                return
            elif msg_type == "error":
                code = data.get("code", "")
                message = data.get("message", "") or code
                if code == "device_disabled":
                    self._dung_han("loi", "Thiết bị đã bị khoá trên server - đã dừng.")
                    return
                self._thong_bao("loi_server", f"Lỗi Server [{code}]: {message}")
                print(f"[Remote] Lỗi từ Server [{code}]: {message}")

    async def _vong_lap_gui(self, ws):
        """Gửi khung mới nhất; trùng khung vừa gửi thì bỏ, trừ khi đã quá 2 giây."""
        khung_truoc = None
        luc_gui = 0.0
        while self._dang_chay:
            try:
                buffer = await asyncio.wait_for(self._queue_khung.get(), timeout=FORCE_RESEND_INTERVAL)
            except asyncio.TimeoutError:
                buffer = self._khung_moi_nhat          # giữ sống: gửi lại khung hiện tại
            if self._vua_ghep_cap:
                self._vua_ghep_cap = False
                khung_truoc = None                     # vừa ghép cặp: gửi bỏ qua lọc trùng
            if not buffer or not self._da_ghep_cap:
                continue
            bay_gio = time.monotonic()
            if buffer == khung_truoc and bay_gio - luc_gui < FORCE_RESEND_INTERVAL:
                continue
            await ws.send(buffer)
            khung_truoc = buffer
            luc_gui = bay_gio
            self.so_khung_da_gui += 1
            self.so_byte_da_gui += len(buffer)

    async def _vong_lap_ping(self, ws):
        while self._dang_chay:
            await asyncio.sleep(PING_INTERVAL)
            await ws.send(json.dumps({"type": "ping"}))

# ============================================================
# 5. CÔNG CỤ CHỌN VÙNG (RegionSelector)
# ============================================================

class RegionSelector(QWidget):
    """Cửa sổ phủ toàn màn hình bán trong suốt để chọn vùng chụp hình chữ nhật."""

    region_selected = pyqtSignal(int, int, int, int)  # x, y, width, height

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self.setCursor(Qt.CursorShape.CrossCursor)

        self._start_pos: QPoint | None = None
        self._current_pos: QPoint | None = None
        self._is_selecting = False

        # Phủ toàn bộ màn hình chính (hoặc ảo đa màn hình)
        screen = QGuiApplication.primaryScreen()
        geo = screen.virtualGeometry() if screen else QRect(0, 0, 1920, 1080)
        self.setGeometry(geo)

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self._start_pos = event.globalPosition().toPoint()
            self._current_pos = self._start_pos
            self._is_selecting = True
            self.update()
        elif event.button() == Qt.MouseButton.RightButton:
            # Click chuột phải để hủy
            self.close()

    def mouseMoveEvent(self, event):
        if self._is_selecting:
            self._current_pos = event.globalPosition().toPoint()
            self.update()

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and self._is_selecting:
            self._is_selecting = False
            rect = self._get_normalized_rect()
            if rect.width() >= 10 and rect.height() >= 10:
                self.region_selected.emit(rect.x(), rect.y(), rect.width(), rect.height())
            self.close()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self.close()

    def _get_normalized_rect(self) -> QRect:
        if not self._start_pos or not self._current_pos:
            return QRect()
        x1 = self._start_pos.x()
        y1 = self._start_pos.y()
        x2 = self._current_pos.x()
        y2 = self._current_pos.y()

        w = abs(x2 - x1)
        # Giữ Shift để chọn kích thước tự do, mặc định khóa tỉ lệ chuẩn 191:62 (như Android HCView)
        is_freeform = bool(QGuiApplication.keyboardModifiers() & Qt.KeyboardModifier.ShiftModifier)
        if not is_freeform:
            h = max(CAO_MAN, round(w * CAO_MAN / RONG_MAN))
        else:
            h = abs(y2 - y1)

        rx = min(x1, x2)
        ry = y1 if y2 >= y1 else y1 - h
        return QRect(rx, ry, max(10, w), max(10, h))

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

        # 1. Vẽ lớp nền xám tối mờ toàn màn hình
        painter.fillRect(self.rect(), QColor(0, 0, 0, 110))

        if self._start_pos and self._current_pos:
            rect = self._get_normalized_rect()
            is_freeform = bool(QGuiApplication.keyboardModifiers() & Qt.KeyboardModifier.ShiftModifier)
            tag = "Tự do" if is_freeform else "Chuẩn 191:62"

            # 2. Xóa vùng trong suốt tại hình chữ nhật được chọn
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Clear)
            painter.fillRect(rect, Qt.GlobalColor.transparent)
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)

            # 3. Vẽ đường viền khung màu xanh nổi bật
            pen = QPen(QColor(59, 130, 246), 2, Qt.PenStyle.SolidLine)
            painter.setPen(pen)
            painter.drawRect(rect)

            # 4. Vẽ nhãn hiển thị kích thước W x H
            txt = f" {rect.width()} × {rect.height()} px [{tag}] (X:{rect.x()}, Y:{rect.y()}) "
            font = QFont("Segoe UI", 10, QFont.Weight.Bold)
            painter.setFont(font)
            fm = painter.fontMetrics()
            txt_w = fm.horizontalAdvance(txt) + 12
            txt_h = fm.height() + 8

            # Đặt vị trí nhãn phía trên hoặc dưới khung
            lbl_x = rect.x()
            lbl_y = rect.y() - txt_h - 4
            if lbl_y < 0:
                lbl_y = rect.y() + 6

            lbl_rect = QRect(lbl_x, lbl_y, txt_w, txt_h)
            painter.fillRect(lbl_rect, QColor(24, 24, 27, 230))
            painter.setPen(QColor(96, 165, 250))
            painter.drawRect(lbl_rect)
            painter.setPen(QColor(255, 255, 255))
            painter.drawText(lbl_rect, Qt.AlignmentFlag.AlignCenter, txt)
        else:
            # Hướng dẫn khi mới mở
            tip = "Kéo rê chuột để chọn vùng tỉ lệ chuẩn 191:62 (giữ Shift để tự do) | Nhấn Esc hoặc chuột phải để hủy"
            font = QFont("Segoe UI", 12, QFont.Weight.Bold)
            painter.setFont(font)
            fm = painter.fontMetrics()
            tw = fm.horizontalAdvance(tip) + 24
            th = fm.height() + 16
            tip_rect = QRect((self.width() - tw) // 2, 40, tw, th)
            painter.fillRect(tip_rect, QColor(24, 24, 27, 230))
            painter.setPen(QColor(59, 130, 246))
            painter.drawRect(tip_rect)
            painter.setPen(QColor(255, 255, 255))
            painter.drawText(tip_rect, Qt.AlignmentFlag.AlignCenter, tip)


# ============================================================
# 6. GIAO DIỆN CẤU HÌNH (HopThoaiCauHinhRelay)
# ============================================================

LIGHT_STYLE = """
QDialog {
    background-color: #ffffff;
    color: #0f172a;
    font-family: "Segoe UI", Arial, sans-serif;
    font-size: 13px;
}
QLabel {
    color: #475569;
    font-size: 13px;
}
QLineEdit, QComboBox, QSpinBox {
    background-color: #ffffff;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 0 10px;
    min-height: 32px;
    color: #0f172a;
    font-size: 13px;
}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus {
    border: 1px solid #2563eb;
    background-color: #ffffff;
}
QRadioButton {
    color: #0f172a;
    font-size: 13px;
    font-weight: 500;
    spacing: 8px;
}
QRadioButton::indicator {
    width: 16px;
    height: 16px;
    border-radius: 9px;
    border: 2px solid #94a3b8;
    background-color: #ffffff;
}
QRadioButton::indicator:hover {
    border-color: #2563eb;
}
QRadioButton::indicator:checked {
    border: 2px solid #2563eb;
    background: qradialgradient(cx:0.5, cy:0.5, radius:0.5, fx:0.5, fy:0.5,
        stop:0 #2563eb, stop:0.5 #2563eb, stop:0.55 #ffffff, stop:1 #ffffff);
}
QRadioButton::indicator:checked:hover {
    border-color: #1d4ed8;
}
QComboBox::drop-down {
    border: none;
    padding-right: 8px;
}
QComboBox QAbstractItemView {
    background-color: #ffffff;
    color: #0f172a;
    border: 1px solid #cbd5e1;
    selection-background-color: #eff6ff;
    selection-color: #1d4ed8;
    padding: 4px;
}
QPushButton {
    background-color: #f8fafc;
    color: #0f172a;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 0 14px;
    min-height: 32px;
    font-weight: 500;
    font-size: 13px;
}
QPushButton:hover {
    background-color: #f1f5f9;
    border-color: #94a3b8;
}
QPushButton:disabled, QPushButton#btnPrimary:disabled, QPushButton#btnAction:disabled {
    background-color: #f1f5f9;
    color: #94a3b8;
    border-color: #e2e8f0;
}
QPushButton#btnPrimary {
    background-color: #2563eb;
    border: 1px solid #1d4ed8;
    color: #ffffff;
    font-size: 13px;
    font-weight: 600;
    min-height: 34px;
    padding: 0 20px;
}
QPushButton#btnPrimary:hover {
    background-color: #1d4ed8;
    border-color: #1e40af;
}
QPushButton#btnAction {
    background-color: #eff6ff;
    border: 1px solid #93c5fd;
    color: #1d4ed8;
    font-weight: 500;
    min-height: 32px;
}
QPushButton#btnAction:hover {
    background-color: #dbeafe;
    border-color: #60a5fa;
    color: #1e40af;
}
QFrame#cardBox {
    background-color: #ffffff;
    border: 1px solid #e2e8f0;
    border-radius: 8px;
    padding: 12px;
}
"""


class HopThoaiCauHinhRelay(QDialog):
    """Hộp thoại cấu hình Remote PC hỗ trợ cả Tự làm Server và Qua Server Relay."""

    ket_noi_thanh_cong = pyqtSignal(dict)
    _login_thanh_cong_sig = pyqtSignal(str, list)
    _login_that_bai_sig = pyqtSignal(str)

    def __init__(self, relay_client=None, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.relay_client = relay_client
        self.cfg = load_config()
        self.danh_sach_thiet_bi = []
        self.access_token = self.cfg.get("token", "")

        self._login_thanh_cong_sig.connect(self._on_login_thanh_cong)
        self._login_that_bai_sig.connect(self._on_login_that_bai)

        self.setWindowTitle("Cấu hình kết nối HCView")
        self.setFixedWidth(460)
        self.setStyleSheet(LIGHT_STYLE)

        self._tao_giao_dien()
        self._nap_du_lieu_cu()

    def _tao_giao_dien(self):
        root = QVBoxLayout(self)
        root.setSpacing(14)
        root.setContentsMargins(20, 20, 20, 20)

        # Header
        header = QHBoxLayout()
        title_box = QVBoxLayout()
        title_box.setSpacing(2)
        title_lbl = QLabel("Cấu hình kết nối HCView")
        title_lbl.setStyleSheet("font-size: 16px; font-weight: bold; color: #0f172a;")
        sub_lbl = QLabel("Chọn cách kết nối vào HCView")
        sub_lbl.setStyleSheet("font-size: 12px; color: #64748b;")
        title_box.addWidget(title_lbl)
        title_box.addWidget(sub_lbl)
        header.addLayout(title_box)
        header.addStretch()
        root.addLayout(header)

        # ================= CHỌN CHẾ ĐỘ =================
        mode_card = QFrame()
        mode_card.setObjectName("cardBox")
        mode_layout = QVBoxLayout(mode_card)
        mode_layout.setSpacing(8)
        mode_layout.setContentsMargins(12, 10, 12, 10)

        mode_title = QLabel("CHẾ ĐỘ KẾT NỐI:")
        mode_title.setStyleSheet("font-size: 11px; font-weight: bold; color: #2563eb; letter-spacing: 0.5px;")
        mode_layout.addWidget(mode_title)

        self.mode_group = QButtonGroup(self)
        self.rb_direct = QRadioButton("Tự làm Server trực tiếp (Khuyên dùng - Cùng WiFi)")
        self.rb_relay = QRadioButton("Kết nối qua Server Relay (Docker / Cloud)")
        self.mode_group.addButton(self.rb_direct)
        self.mode_group.addButton(self.rb_relay)

        mode_layout.addWidget(self.rb_direct)
        mode_layout.addWidget(self.rb_relay)
        root.addWidget(mode_card)

        # ================= KHUNG 1: TỰ LÀM SERVER TRỰC TIẾP =================
        self.card_direct = QFrame()
        self.card_direct.setObjectName("cardBox")
        direct_form = QFormLayout(self.card_direct)
        direct_form.setSpacing(10)
        direct_form.setContentsMargins(12, 12, 12, 12)

        # Cổng nghe
        self.spin_port = QSpinBox()
        self.spin_port.setRange(1024, 65535)
        self.spin_port.setValue(self.cfg.get("direct_port", 8268))
        self.spin_port.valueChanged.connect(self._cap_nhat_url_direct)
        direct_form.addRow("Cổng Server (Port):", self.spin_port)

        # Địa chỉ kết nối (ip:cổng - tự thêm /ws/remote)
        self.txt_direct_url = QLineEdit()
        self.txt_direct_url.setReadOnly(True)
        self.txt_direct_url.setStyleSheet("font-family: Consolas, monospace; font-weight: bold; color: #1d4ed8; background-color: #f8fafc;")
        
        row_url = QHBoxLayout()
        row_url.addWidget(self.txt_direct_url)
        self.btn_copy_url = QPushButton("Sao chép")
        self.btn_copy_url.setFixedWidth(88)
        self.btn_copy_url.setStyleSheet("padding: 0 8px;")
        self.btn_copy_url.clicked.connect(self._copy_direct_url)
        row_url.addWidget(self.btn_copy_url)
        direct_form.addRow("Địa chỉ kết nối:", row_url)

        lbl_direct_note = QLabel("Nhập địa chỉ trên vào thiết bị kết nối")
        lbl_direct_note.setStyleSheet("font-size: 11.5px; color: #64748b;")
        direct_form.addRow("", lbl_direct_note)
        root.addWidget(self.card_direct)

        # ================= KHUNG 2: QUA SERVER RELAY =================
        self.card_relay = QFrame()
        self.card_relay.setObjectName("cardBox")
        relay_form = QFormLayout(self.card_relay)
        relay_form.setSpacing(10)
        relay_form.setContentsMargins(12, 12, 12, 12)

        self.txt_server = QLineEdit()
        self.txt_server.setPlaceholderText("http://localhost:8000")
        relay_form.addRow("Địa chỉ Server:", self.txt_server)

        self.txt_user = QLineEdit()
        self.txt_user.setPlaceholderText("Tên đăng nhập (username)")
        relay_form.addRow("Tài khoản:", self.txt_user)

        self.txt_pass = QLineEdit()
        self.txt_pass.setEchoMode(QLineEdit.EchoMode.Password)
        self.txt_pass.setPlaceholderText("Mật khẩu")
        relay_form.addRow("Mật khẩu:", self.txt_pass)

        # Nút Đăng nhập và Tải thiết bị
        row_login = QHBoxLayout()
        self.btn_login = QPushButton("Đăng nhập và tải thiết bị")
        self.btn_login.setObjectName("btnAction")
        self.btn_login.clicked.connect(self._xu_ly_dang_nhap)
        row_login.addWidget(self.btn_login)
        relay_form.addRow("", row_login)

        # Danh sách thiết bị
        self.cb_device = QComboBox()
        relay_form.addRow("Chọn thiết bị:", self.cb_device)

        # Trạng thái đăng nhập
        self.lbl_login_status = QLabel("")
        self.lbl_login_status.setStyleSheet("font-size: 11px; color: #b45309; font-weight: 500;")
        relay_form.addRow("", self.lbl_login_status)
        root.addWidget(self.card_relay)

        # Sự kiện chuyển chế độ
        self.rb_direct.toggled.connect(self._chuyen_che_do)
        self.rb_relay.toggled.connect(self._chuyen_che_do)

        # Action Buttons
        btn_box = QHBoxLayout()
        btn_box.setSpacing(10)

        self.btn_huy = QPushButton("Hủy")
        self.btn_huy.clicked.connect(self.reject)
        btn_box.addWidget(self.btn_huy)

        self.btn_luu = QPushButton("Lưu và kết nối")
        self.btn_luu.setObjectName("btnPrimary")
        self.btn_luu.clicked.connect(self._on_luu_ket_noi)
        btn_box.addWidget(self.btn_luu)

        root.addLayout(btn_box)

    def _cap_nhat_url_direct(self):
        port = self.spin_port.value()
        ip = lay_ip_lan()
        self.txt_direct_url.setText(f"{ip}:{port}")

    def _copy_direct_url(self):
        QApplication.clipboard().setText(self.txt_direct_url.text())

    def _chuyen_che_do(self):
        is_direct = self.rb_direct.isChecked()
        self.card_direct.setVisible(is_direct)
        self.card_relay.setVisible(not is_direct)
        self.adjustSize()

    def _nap_du_lieu_cu(self):
        che_do = self.cfg.get("che_do", "direct_server")
        if che_do == "direct_server":
            self.rb_direct.setChecked(True)
        else:
            self.rb_relay.setChecked(True)
        self._chuyen_che_do()

        self.spin_port.setValue(self.cfg.get("direct_port", 8268))
        self._cap_nhat_url_direct()

        self.txt_server.setText(self.cfg.get("server_url", "http://localhost:8000"))
        self.txt_user.setText(self.cfg.get("username", "admin"))
        self.txt_pass.setText(self.cfg.get("password", ""))

        # Khởi tạo placeholder thiết bị
        old_dev_id = self.cfg.get("device_id", "")
        self.cb_device.clear()
        if old_dev_id:
            self.cb_device.addItem(f"Thiết bị đã chọn ({old_dev_id})", old_dev_id)
        else:
            self.cb_device.addItem("-- Bấm 'Đăng nhập & Tải thiết bị' --", "")

    def _xu_ly_dang_nhap(self):
        server_url = self.txt_server.text().strip()
        user = self.txt_user.text().strip()
        pwd = self.txt_pass.text()

        if not server_url or not user:
            self.lbl_login_status.setText("Vui lòng nhập địa chỉ server và tài khoản")
            self.lbl_login_status.setStyleSheet("font-size: 11px; color: #b45309; font-weight: 500;")
            return

        self.btn_login.setEnabled(False)
        self.lbl_login_status.setText("Đang đăng nhập...")
        self.lbl_login_status.setStyleSheet("font-size: 11px; color: #2563eb; font-weight: 500;")

        def _worker():
            try:
                token = dang_nhap_server(server_url, user, pwd)
                if not token:
                    self._login_that_bai_sig.emit("Tên đăng nhập hoặc mật khẩu không chính xác")
                    return

                devs = lay_danh_sach_thiet_bi(server_url, token)
                self._login_thanh_cong_sig.emit(token, devs)
            except Exception as e:
                self._login_that_bai_sig.emit(str(e))

        threading.Thread(target=_worker, daemon=True).start()

    def _on_login_thanh_cong(self, token: str, devs: list):
        self.btn_login.setEnabled(True)
        self.access_token = token
        self.danh_sach_thiet_bi = devs

        self.cb_device.clear()
        if not devs:
            self.cb_device.addItem("-- Tài khoản chưa có thiết bị nào --", "")
            self.lbl_login_status.setText("Đăng nhập thành công nhưng chưa có thiết bị")
            self.lbl_login_status.setStyleSheet("font-size: 11px; color: #b45309; font-weight: 500;")
            return

        idx_to_select = 0
        current_dev_id = self.cfg.get("device_id", "")
        for i, d in enumerate(devs):
            dev_id = d.get("device_id", "")
            name = d.get("name") or dev_id
            self.cb_device.addItem(f"{name} ({dev_id})", dev_id)
            if dev_id == current_dev_id:
                idx_to_select = i

        self.cb_device.setCurrentIndex(idx_to_select)
        self.lbl_login_status.setText(f"Đăng nhập thành công ({len(devs)} thiết bị)")
        self.lbl_login_status.setStyleSheet("font-size: 11px; color: #15803d; font-weight: 500;")

    def _on_login_that_bai(self, err_msg: str):
        self.btn_login.setEnabled(True)
        self.lbl_login_status.setText(f"Lỗi: {err_msg}")
        self.lbl_login_status.setStyleSheet("font-size: 11px; color: #b91c1c; font-weight: 500;")

    def _on_luu_ket_noi(self):
        is_direct = self.rb_direct.isChecked()
        che_do = "direct_server" if is_direct else "relay_server"

        new_cfg = dict(self.cfg)
        new_cfg["che_do"] = che_do

        if is_direct:
            new_cfg["direct_port"] = self.spin_port.value()
        else:
            server_url = self.txt_server.text().strip() or "http://localhost:8000"
            user = self.txt_user.text().strip()
            pwd = self.txt_pass.text()
            dev_id = self.cb_device.currentData()

            if not dev_id:
                QMessageBox.warning(self, "Chưa chọn thiết bị", "Vui lòng đăng nhập và chọn một thiết bị ESP32 để kết nối.")
                return

            new_cfg["server_url"] = server_url
            new_cfg["username"] = user
            new_cfg["password"] = pwd
            new_cfg["device_id"] = dev_id
            new_cfg["token"] = self.access_token

        save_config(new_cfg)
        self.ket_noi_thanh_cong.emit(new_cfg)
        self.accept()


# ============================================================
# 7. CỬA SỔ CHÍNH (RemotePCWindow)
# ============================================================

MUC_ZOOM = (1.0, 1.5, 2.0, 3.0)

# Các nấc tốc độ cuộn cho V+/V- (khớp app Android, xem REMOTE_PC_ESP32_SCROLL.md)
MUC_TOC_DO_CUON = (0.5, 1.0, 1.5, 2.0)

# Chưa chia sẻ: vẫn chụp thưa để xem trước khi chỉnh vùng / chế độ ảnh
KHOANG_XEM_TRUOC_MS = 500

# Bảng màu màn hình LCD Trắng - Đen - Xám (4 mức xám: 00 trắng/nền, 01 xám nhạt, 10 xám đậm, 11 đen)
MAU_LCD = np.array([
    [255, 255, 255],  # 00: Trắng tinh (Nền)
    [170, 170, 170],  # 01: Xám nhạt
    [85, 85, 85],     # 10: Xám đậm
    [0, 0, 0],         # 11: Đen tuyền
], dtype=np.uint8)
MAU_LCD_CLASSIC = MAU_LCD

# Màu nhãn trạng thái: (chữ, nền, viền)
MAU_XANH_LA = ("#047857", "#ecfdf5", "#a7f3d0")
MAU_XANH = ("#1d4ed8", "#eff6ff", "#bfdbfe")
MAU_VANG = ("#b45309", "#fffbeb", "#fde68a")
MAU_DO = ("#b91c1c", "#fef2f2", "#fecaca")
MAU_XAM = ("#475569", "#f1f5f9", "#e2e8f0")
MAU_TRANG_THAI = {
    "da_ghep_cap": MAU_XANH_LA,
    "da_noi": MAU_XANH,
    "cho_thiet_bi": MAU_VANG,
    "dang_ket_noi": MAU_VANG,
    "dang_dang_nhap": MAU_VANG,
    "thiet_bi_ngat": MAU_VANG,
    "mat_ket_noi": MAU_VANG,
    "loi": MAU_DO,
    "loi_server": MAU_DO,
}


def _nac_ke_tiep(cac_muc: tuple, hien_tai: float, chieu: str) -> float:
    """Z+/Z-/V+/V-: lấy nấc gần mức hiện tại nhất rồi đi 1 nấc theo chiều '+'/'-' (dừng ở 2 đầu)."""
    gan_nhat = min(range(len(cac_muc)), key=lambda i: abs(cac_muc[i] - hien_tai))
    i = min(gan_nhat + 1, len(cac_muc) - 1) if chieu == "+" else max(gan_nhat - 1, 0)
    return cac_muc[i]


class PreviewLcd(QWidget):
    """Xem trước màn hình LCD: to rõ, vi điểm LCD sắc nét không mờ nhòe."""

    def __init__(self, scale: int = 3, parent=None, **_kwargs):
        super().__init__(parent)
        self._muc = np.zeros((CAO_MAN, RONG_MAN), dtype=np.uint8)
        self._anh = None
        self.scale_factor = scale
        self.setFixedSize(RONG_MAN * self.scale_factor, CAO_MAN * self.scale_factor)
        self._ve_lai()

    def update_frame(self, khung: bytes):
        self._muc = giai_ma_muc(khung)
        self._ve_lai()

    def _ve_lai(self):
        s = self.scale_factor
        mau = MAU_LCD
        rgb = np.repeat(np.repeat(mau[self._muc], s, axis=0), s, axis=1)
        cao, rong = rgb.shape[:2]
        du_lieu = np.ascontiguousarray(rgb).tobytes()
        self._anh = QImage(du_lieu, rong, cao, rong * 3, QImage.Format.Format_RGB888).copy()
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        if self._anh is not None:
            painter.drawImage(0, 0, self._anh)
        else:
            painter.fillRect(self.rect(), QColor(*MAU_LCD[0]))
        # Viền định hình màn hình LCD màu xám nhẹ
        pen = QPen(QColor(203, 213, 225), 1)
        painter.setPen(pen)
        painter.drawRect(0, 0, self.width() - 1, self.height() - 1)


class RemotePCWindow(QMainWindow):
    """Cửa sổ chính của ứng dụng Remote PC."""

    lenh_tu_esp32 = pyqtSignal(str)
    trang_thai_relay = pyqtSignal(object, str, str)   # (nguồn, mã, mô tả) - bỏ tin của kết nối cũ
    token_moi = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.cfg = _doc_cau_hinh_remote()

        self.ket_noi = None            # DirectServer hoặc RelayClient
        self.selector_window: RegionSelector | None = None
        self._dia_chi_chinh = ""

        self._is_streaming = False
        self._khung_preview: bytes | None = None
        self._ds_thiet_bi_truoc: set = set()
        self._so_khung_chup = 0
        self._luc_do_fps = time.monotonic()
        self._fps_chup = 0.0
        self._dang_nap_giao_dien = True

        # Zoom (lệnh Z): vùng người dùng tự chọn là 1.0x, mức z thì vùng còn
        # 1/z kích thước quanh tâm. Đặt TRƯỚC _tao_giao_dien() vì
        # _luu_vung_tu_spin() đọc 2 biến này.
        self._zoom = float(self.cfg.get("region_zoom", 1.0))
        self._dang_dat_zoom = False
        # Kích thước vùng ở 1.0x - nhớ thẳng (không tính lại từ kích thước đã
        # làm tròn mỗi lần) để đổi zoom qua lại nhiều lần vẫn về đúng vùng gốc.
        self._vung_goc = (
            round(self.cfg.get("region_w", 286) * self._zoom),
            round(self.cfg.get("region_h", 93) * self._zoom),
        )

        admin_tag = " [Admin]" if is_admin() else ""
        self.setWindowTitle(f"HCView{admin_tag}")
        self.resize(700, 620)
        self.setMinimumWidth(680)

        # Ghi remote_config.json sau 0,4 giây yên lặng (lệnh M đổi x rồi y = 1 lần ghi)
        self.luu_timer = QTimer(self)
        self.luu_timer.setSingleShot(True)
        self.luu_timer.timeout.connect(lambda: _ghi_cau_hinh_remote(self.cfg))

        # Chụp + gửi ngay ở vòng sự kiện kế tiếp (gộp nhiều thay đổi liền nhau)
        self.khung_ngay_timer = QTimer(self)
        self.khung_ngay_timer.setSingleShot(True)
        self.khung_ngay_timer.timeout.connect(self._chup_va_gui_khung)

        self._tao_giao_dien()
        self._ap_dung_stylesheet()
        self._dang_nap_giao_dien = False

        self.lenh_tu_esp32.connect(self._xu_ly_lenh_esp32)
        self.trang_thai_relay.connect(self._xu_ly_trang_thai_relay)
        self.token_moi.connect(self._luu_token_moi)

        # Nhịp chụp: theo FPS khi chia sẻ, thưa (xem trước) khi chưa
        self.stream_timer = QTimer(self)
        self.stream_timer.timeout.connect(self._chup_va_gui_khung)
        self.stream_timer.start(KHOANG_XEM_TRUOC_MS)

        self.fps_timer = QTimer(self)
        self.fps_timer.timeout.connect(self._cap_nhat_thong_ke)
        self.fps_timer.start(1000)

        self._khoi_dong_ket_noi()

    # ================= GIAO DIỆN =================

    def _tao_giao_dien(self):
        central = QWidget()
        self.setCentralWidget(central)
        root_layout = QVBoxLayout(central)
        root_layout.setSpacing(6)
        root_layout.setContentsMargins(10, 8, 10, 8)

        # Thanh tiêu đề & kết nối gọn gàng
        top_bar = QHBoxLayout()
        top_bar.setSpacing(8)
        lbl_title = QLabel("HCView")
        lbl_title.setStyleSheet("font-size: 15px; font-weight: bold; color: #2563eb;")
        top_bar.addWidget(lbl_title)
        top_bar.addSpacing(6)

        self.lbl_dia_chi = QLabel("")
        top_bar.addWidget(self.lbl_dia_chi)

        self.btn_copy_dia_chi = QPushButton("Sao chép")
        self.btn_copy_dia_chi.setFixedWidth(64)
        self.btn_copy_dia_chi.clicked.connect(self._sao_chep_dia_chi)
        top_bar.addWidget(self.btn_copy_dia_chi)

        top_bar.addSpacing(8)
        self.lbl_thiet_bi = QLabel("Thiết bị: <span style='color:#94a3b8'>chưa kết nối</span>")
        top_bar.addWidget(self.lbl_thiet_bi)

        top_bar.addStretch()

        self.lbl_relay_badge = QLabel("Chờ kết nối")
        top_bar.addWidget(self.lbl_relay_badge)

        self.btn_config_relay = QPushButton("Cài đặt...")
        self.btn_config_relay.setFixedWidth(75)
        self.btn_config_relay.clicked.connect(self._mo_cau_hinh_relay)
        top_bar.addWidget(self.btn_config_relay)
        root_layout.addLayout(top_bar)

        # ----- Khung xem trước màn hình Thiết bị (gọn gàng 3x: 573 x 186 px, nét đanh) -----
        grp_preview = QGroupBox("Màn hình LCD")
        prev_layout = QVBoxLayout(grp_preview)
        prev_layout.setSpacing(4)
        self.preview_canvas = PreviewLcd(scale=3)
        prev_layout.addWidget(self.preview_canvas, 0, Qt.AlignmentFlag.AlignHCenter)

        self.lbl_stats = QLabel("")
        self.lbl_stats.setStyleSheet("color: #64748b; font-size: 11px;")
        self.lbl_stats.setAlignment(Qt.AlignmentFlag.AlignCenter)
        prev_layout.addWidget(self.lbl_stats)
        root_layout.addWidget(grp_preview)

        than = QHBoxLayout()
        than.setSpacing(12)

        # ----- Nhật ký lệnh -----
        grp_log = QGroupBox("Nhật ký lệnh")
        log_layout = QVBoxLayout(grp_log)
        self.txt_log = QTextEdit()
        self.txt_log.setReadOnly(True)
        self.txt_log.document().setMaximumBlockCount(300)
        self.txt_log.setPlaceholderText("Chưa có lệnh nào...")
        self.txt_log.setStyleSheet("background-color: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; font-family: Consolas, monospace; font-size: 11px; color: #0f172a;")
        self.txt_log.setMinimumHeight(60)
        log_layout.addWidget(self.txt_log)

        # ----- Phải: vùng chụp màn hình -----
        grp_region = QGroupBox("Vùng chụp màn hình")
        region_layout = QVBoxLayout(grp_region)
        region_layout.setSpacing(8)

        btn_select_region = QPushButton("Chọn vùng bằng chuột...")
        btn_select_region.setStyleSheet("background-color: #2563eb; color: #ffffff; font-weight: bold; padding: 6px; border-radius: 6px;")
        btn_select_region.clicked.connect(self._bat_dau_chon_vung)
        region_layout.addWidget(btn_select_region)

        self.spin_x = QSpinBox()
        self.spin_x.setRange(-7680, 15360)
        self.spin_x.setValue(self.cfg.get("region_x", 817))
        self.spin_y = QSpinBox()
        self.spin_y.setRange(-4320, 8640)
        self.spin_y.setValue(self.cfg.get("region_y", 493))
        self.spin_w = QSpinBox()
        self.spin_w.setRange(10, 15360)
        self.spin_w.setValue(self.cfg.get("region_w", 286))
        self.spin_h = QSpinBox()
        self.spin_h.setRange(10, 8640)
        self.spin_h.setValue(self.cfg.get("region_h", 93))

        luoi_toa_do = QGridLayout()
        luoi_toa_do.setHorizontalSpacing(6)
        luoi_toa_do.setVerticalSpacing(6)
        for i, (ten, spin) in enumerate((("X", self.spin_x), ("Y", self.spin_y),
                                         ("Rộng", self.spin_w), ("Cao", self.spin_h))):
            spin.valueChanged.connect(self._luu_vung_tu_spin)
            luoi_toa_do.addWidget(QLabel(ten + ":"), i // 2, (i % 2) * 2)
            luoi_toa_do.addWidget(spin, i // 2, (i % 2) * 2 + 1)
        luoi_toa_do.setColumnStretch(1, 1)
        luoi_toa_do.setColumnStretch(3, 1)
        region_layout.addLayout(luoi_toa_do)

        row_zoom = QHBoxLayout()
        row_zoom.addWidget(QLabel("Thu phóng:"))
        self.nhom_zoom = QButtonGroup(self)
        self.nhom_zoom.setExclusive(True)
        self.btn_zoom = {}
        for z in MUC_ZOOM:
            b = QPushButton(f"{z:g}x")
            b.setCheckable(True)
            b.setFixedWidth(46)
            b.clicked.connect(lambda _, zz=z: self._dat_zoom_tu_giao_dien(zz))
            self.nhom_zoom.addButton(b)
            self.btn_zoom[z] = b
            row_zoom.addWidget(b)
        row_zoom.addStretch()
        region_layout.addLayout(row_zoom)

        row_tien_ich = QHBoxLayout()
        btn_center = QPushButton("Căn giữa")
        btn_center.clicked.connect(self._preset_dai_giua)
        btn_fit = QPushButton("Khớp tỉ lệ 191:62")
        btn_fit.clicked.connect(self._preset_khop_ti_le)
        row_tien_ich.addWidget(btn_center)
        row_tien_ich.addWidget(btn_fit)
        region_layout.addLayout(row_tien_ich)

        # ----- Phải: cài đặt điều khiển & hình ảnh -----
        grp_display = QGroupBox("Cài đặt điều khiển")
        disp_layout = QFormLayout(grp_display)
        disp_layout.setSpacing(8)

        self.cb_net = QComboBox()
        self.cb_net.addItem("Tắt", MUC_NET_TAT)
        self.cb_net.addItem("Cân bằng", MUC_NET_CAN_BANG)
        self.cb_net.addItem("Cao", MUC_NET_CAO)
        self.cb_net.setCurrentIndex(max(0, self.cb_net.findData(int(self.cfg.get("muc_net", MUC_NET_CAN_BANG)))))
        self.cb_net.currentIndexChanged.connect(self._doi_xu_ly_anh)
        disp_layout.addRow("Tăng nét chữ:", self.cb_net)

        self.spin_fps = QSpinBox()
        self.spin_fps.setRange(1, 30)
        self.spin_fps.setSuffix(" fps")
        self.spin_fps.setValue(int(self.cfg.get("fps", 5)))
        self.spin_fps.valueChanged.connect(self._doi_fps)
        disp_layout.addRow("Tốc độ gửi:", self.spin_fps)

        self.cb_toc_do_cuon = QComboBox()
        for v in MUC_TOC_DO_CUON:
            self.cb_toc_do_cuon.addItem(f"{v:g}x", v)
        self._chon_toc_do_cuon(float(self.cfg.get("toc_do_cuon", 1.0)))
        self.cb_toc_do_cuon.currentIndexChanged.connect(self._doi_toc_do_cuon)
        disp_layout.addRow("Tốc độ cuộn:", self.cb_toc_do_cuon)

        self.chk_invert = QCheckBox("Đảo màu (nền đen chữ trắng)")
        self.chk_invert.setChecked(bool(self.cfg.get("invert", False)))
        self.chk_invert.toggled.connect(self._doi_xu_ly_anh)
        disp_layout.addRow("", self.chk_invert)

        cot_phai = QVBoxLayout()
        cot_phai.setSpacing(8)
        cot_phai.addWidget(grp_display)
        cot_phai.addWidget(grp_log, 1)

        than.addWidget(grp_region, 1)
        than.addLayout(cot_phai, 1)
        root_layout.addLayout(than, 1)

        self.btn_stream = QPushButton()
        self.btn_stream.clicked.connect(self._toggle_stream)
        root_layout.addWidget(self.btn_stream)
        self._cap_nhat_nut_chia_se()
        self._cap_nhat_nut_zoom()

    def _ap_dung_stylesheet(self):
        self.setStyleSheet("""
            QMainWindow {
                background-color: #ffffff;
                color: #0f172a;
                font-family: "Segoe UI", Arial, sans-serif;
            }
            QWidget {
                background-color: #ffffff;
            }
            QGroupBox {
                background-color: #ffffff;
                border: 1px solid #e2e8f0;
                border-radius: 8px;
                margin-top: 10px;
                padding-top: 14px;
                font-weight: 600;
                color: #0f172a;
            }
            QGroupBox::title {
                subcontrol-origin: margin;
                subcontrol-position: top left;
                padding: 0 6px;
                color: #2563eb;
            }
            QLabel {
                color: #334155;
            }
            QLineEdit, QSpinBox, QComboBox, QTextEdit {
                background-color: #ffffff;
                border: 1px solid #cbd5e1;
                border-radius: 6px;
                padding: 2px 8px;
                color: #0f172a;
                font-size: 13px;
                min-height: 26px;
            }
            QLineEdit:focus, QSpinBox:focus, QComboBox:focus, QTextEdit:focus {
                border: 1px solid #2563eb;
            }
            QPushButton {
                background-color: #f8fafc;
                color: #0f172a;
                border: 1px solid #cbd5e1;
                border-radius: 6px;
                padding: 4px 10px;
                font-weight: 500;
                min-height: 26px;
            }
            QPushButton:hover {
                background-color: #f1f5f9;
                border-color: #94a3b8;
            }
            QPushButton:checked {
                background-color: #2563eb;
                border-color: #1d4ed8;
                color: #ffffff;
            }
            QCheckBox {
                color: #334155;
                font-size: 13px;
                spacing: 6px;
            }
            QCheckBox::indicator {
                width: 16px;
                height: 16px;
            }
        """)

    def _cap_nhat_nut_chia_se(self):
        if self._is_streaming:
            self.btn_stream.setText("Dừng chia sẻ")
            mau = "#dc2626"
        else:
            self.btn_stream.setText("Bắt đầu chia sẻ")
            mau = "#16a34a"
        self.btn_stream.setStyleSheet(
            f"background-color: {mau}; color: #ffffff; font-size: 13px; font-weight: bold; min-height: 36px; border-radius: 6px;"
        )

    def _hien_trang_thai(self, ma: str, mo_ta: str):
        chu, nen, vien = MAU_TRANG_THAI.get(ma, MAU_XAM)
        tag = "Nối thẳng" if self._la_noi_thang() else "Relay"
        self.lbl_relay_badge.setText(f"{tag}: {mo_ta}")
        self.lbl_relay_badge.setStyleSheet(
            f"background-color: {nen}; color: {chu}; border: 1px solid {vien}; border-radius: 10px; padding: 4px 10px; font-size: 11px; font-weight: 600;"
        )

    def _cap_nhat_dia_chi(self):
        if self._la_noi_thang():
            port = self.cfg.get("direct_port", 8268)
            ip = lay_ip_lan()
            self._dia_chi_chinh = f"{ip}:{port}"
            self.lbl_dia_chi.setText(f"IP: <b>{self._dia_chi_chinh}</b>")
            self.btn_copy_dia_chi.setVisible(True)
        else:
            self._dia_chi_chinh = ""
            self.lbl_dia_chi.setText(f"Relay: <b>{self.cfg.get('server_url', '')}</b>")
            self.btn_copy_dia_chi.setVisible(False)

    def _sao_chep_dia_chi(self):
        if self._dia_chi_chinh:
            QApplication.clipboard().setText(self._dia_chi_chinh)
            self._ghi_log(f"Đã sao chép địa chỉ {self._dia_chi_chinh}")

    def _cap_nhat_nut_zoom(self):
        """Sáng nút đúng mức zoom hiện tại; mức lẻ (Z1.2...) thì không nút nào sáng."""
        self.nhom_zoom.setExclusive(False)
        for z, nut in self.btn_zoom.items():
            nut.setChecked(abs(z - self._zoom) < 0.01)
        self.nhom_zoom.setExclusive(True)

    def _chon_toc_do_cuon(self, v: float):
        i = min(range(len(MUC_TOC_DO_CUON)), key=lambda k: abs(MUC_TOC_DO_CUON[k] - v))
        self.cb_toc_do_cuon.blockSignals(True)
        self.cb_toc_do_cuon.setCurrentIndex(i)
        self.cb_toc_do_cuon.blockSignals(False)

    def _ghi_log(self, dong: str):
        self.txt_log.append(f"[{time.strftime('%H:%M:%S')}] {dong}")

    # ================= CÀI ĐẶT =================

    def _hen_luu(self):
        self.luu_timer.start(400)

    def _yeu_cau_khung_ngay(self):
        """Chụp + gửi ngay (dời vùng, zoom, đổi chế độ ảnh) thay vì chờ nhịp FPS."""
        if not self._dang_nap_giao_dien:
            self.khung_ngay_timer.start(0)

    def _doi_xu_ly_anh(self):
        self.cfg["muc_net"] = int(self.cb_net.currentData())
        self.cfg["invert"] = self.chk_invert.isChecked()
        self._hen_luu()
        self._yeu_cau_khung_ngay()

    def _doi_fps(self, val: int):
        self.cfg["fps"] = val
        self._hen_luu()
        if self._is_streaming:
            self.stream_timer.setInterval(max(1, 1000 // val))

    def _doi_toc_do_cuon(self):
        self.cfg["toc_do_cuon"] = float(self.cb_toc_do_cuon.currentData())
        self._hen_luu()

    def _doi_pan(self, phan_tram: int):
        self.cfg["pan_ty_le"] = phan_tram / 100.0
        self._hen_luu()

    def _luu_token_moi(self, token: str):
        self.cfg["token"] = token
        _ghi_cau_hinh_remote(self.cfg)

    # ================= VÙNG MÀN HÌNH =================

    def _man_hinh_ao(self) -> QRect:
        screen = QGuiApplication.primaryScreen()
        return screen.virtualGeometry() if screen else QRect(0, 0, 7680, 4320)

    def _man_hinh_chinh(self) -> QRect:
        screen = QGuiApplication.primaryScreen()
        return screen.geometry() if screen else QRect(0, 0, 1920, 1080)

    def _dat_vung(self, x: int, y: int, w: int, h: int):
        """Đặt vùng (kẹp trong màn hình). Gọi _luu_vung_tu_spin 1 lần ở cuối."""
        geo = self._man_hinh_ao()
        w = max(10, min(w, geo.width()))
        h = max(10, min(h, geo.height()))
        x = max(geo.x(), min(x, geo.x() + geo.width() - w))
        y = max(geo.y(), min(y, geo.y() + geo.height() - h))
        for spin, v in ((self.spin_x, x), (self.spin_y, y), (self.spin_w, w), (self.spin_h, h)):
            spin.blockSignals(True)
            spin.setValue(v)
            spin.blockSignals(False)
        self._luu_vung_tu_spin()

    def _bat_dau_chon_vung(self):
        """Mở lớp phủ để kéo chuột chọn vùng."""
        self.selector_window = RegionSelector()
        self.selector_window.region_selected.connect(self._dat_vung)
        self.selector_window.show()

    def _luu_vung_tu_spin(self):
        # Người dùng tự đổi KÍCH THƯỚC vùng (chọn bằng chuột, gõ số, preset):
        # vùng mới chính là mức 1.0x. Dời vùng (M) chỉ đổi x/y nên giữ zoom.
        if not self._dang_dat_zoom and (self.spin_w.value(), self.spin_h.value()) != (
            self.cfg.get("region_w"), self.cfg.get("region_h")
        ):
            geo = self._man_hinh_chinh()
            w = self.spin_w.value()
            if w > 0:
                w_map = {1.0: 382, 1.5: 286, 2.0: 240, 3.0: 191}
                gan_nhat = min(w_map.keys(), key=lambda k: abs(w_map[k] - w))
                self._zoom = gan_nhat if abs(w_map[gan_nhat] - w) < 25 else 1.5
                self._cap_nhat_nut_zoom()
        self.cfg["region_zoom"] = self._zoom
        self.cfg["region_x"] = self.spin_x.value()
        self.cfg["region_y"] = self.spin_y.value()
        self.cfg["region_w"] = self.spin_w.value()
        self.cfg["region_h"] = self.spin_h.value()
        self._hen_luu()
        self._yeu_cau_khung_ngay()

    def _preset_khop_ti_le(self):
        """Giữ tâm và chiều rộng, chỉnh chiều cao khớp chuẩn 191:62."""
        w = self.spin_w.value()
        h = max(CAO_MAN, round(w * CAO_MAN / RONG_MAN))
        tam_y = self.spin_y.value() + self.spin_h.value() // 2
        self._dat_vung(self.spin_x.value(), tam_y - h // 2, w, h)

    def _preset_dai_giua(self):
        """Dời vùng hiện tại vào chính giữa màn hình."""
        geo = self._man_hinh_chinh()
        w = self.spin_w.value()
        h = self.spin_h.value()
        self._dat_vung(geo.x() + (geo.width() - w) // 2, geo.y() + (geo.height() - h) // 2, w, h)

    def _dat_zoom_tu_giao_dien(self, z: float):
        self._ghi_log(self._dat_zoom(f"{z}"))

    def _dat_zoom(self, tham_so: str) -> str:
        """Đổi mức zoom: áp dụng chuẩn tỉ lệ dải ngang của Android (REMOTE_PC_ESP32_ZOOM.md),
        rw = screen_w / zoom, rh = rw * 62 / 191, giữ nguyên tâm khung hình."""
        tham_so = tham_so.strip()
        if tham_so in ("+", "-"):
            z = _nac_ke_tiep(MUC_ZOOM, self._zoom, tham_so)
        else:
            try:
                z = float(tham_so)
            except ValueError:
                return "mức zoom không hợp lệ"
        z = max(1.0, min(z, 4.0))

        geo = self._man_hinh_chinh()
        screen_w = geo.width()
        screen_h = geo.height()

        w_map = {1.0: 382, 1.5: 286, 2.0: 240, 3.0: 191}
        rw = w_map.get(z, max(RONG_MAN, min(screen_w, round(382 / z))))
        rh = max(CAO_MAN, round(rw * CAO_MAN / RONG_MAN))

        tam_x = self.spin_x.value() + self.spin_w.value() // 2
        tam_y = self.spin_y.value() + self.spin_h.value() // 2

        new_x = max(geo.x(), min(geo.x() + screen_w - rw, tam_x - rw // 2))
        new_y = max(geo.y(), min(geo.y() + screen_h - rh, tam_y - rh // 2))

        self._dang_dat_zoom = True
        try:
            self._zoom = z
            self._dat_vung(new_x, new_y, rw, rh)
        finally:
            self._dang_dat_zoom = False
        self._cap_nhat_nut_zoom()
        return f"zoom {z:g}x: vùng ({self.spin_x.value()}, {self.spin_y.value()}) {self.spin_w.value()}x{self.spin_h.value()}"

    # ================= CHIA SẺ HÌNH =================

    def _la_noi_thang(self) -> bool:
        return self.cfg.get("che_do", "direct_server") == "direct_server"

    def _toggle_stream(self):
        self._dat_chia_se(not self._is_streaming)

    def _dat_chia_se(self, bat: bool, bao_trang_thai: bool = True):
        self._is_streaming = bat
        self._cap_nhat_nut_chia_se()
        if bat:
            self._chup_va_gui_khung()      # có sẵn khung để gửi ngay lúc ghép cặp
            self.stream_timer.start(max(1, 1000 // max(1, int(self.cfg.get("fps", 5)))))
        else:
            self.stream_timer.start(KHOANG_XEM_TRUOC_MS)
        self._ap_dung_chia_se(bao_trang_thai)

    def _ap_dung_chia_se(self, bao_trang_thai: bool = True):
        if self._la_noi_thang():
            if self.ket_noi is not None:
                if self._is_streaming:
                    self.ket_noi.bat_dau()      # cổng từng bận: thử mở lại
                self.ket_noi.dat_chia_se(self._is_streaming)
            return
        # Relay: chỉ ở trong server khi đang chia sẻ
        if self._is_streaming and self.ket_noi is None:
            self.ket_noi = self._tao_relay()
            if self._khung_preview:
                self.ket_noi.gui_khung(self._khung_preview)   # có sẵn để gửi ngay lúc ghép cặp
            self.ket_noi.bat_dau()
        elif not self._is_streaming and self.ket_noi is not None:
            self.ket_noi.dung()
            self.ket_noi = None
            if bao_trang_thai:
                self._hien_trang_thai("", "chưa chia sẻ - bấm Bắt đầu để vào server")

    def _chup_va_gui_khung(self):
        screen = QGuiApplication.primaryScreen()
        if not screen:
            return
        pixmap = screen.grabWindow(0, self.spin_x.value(), self.spin_y.value(),
                                   self.spin_w.value(), self.spin_h.value())
        if pixmap.isNull():
            return

        khung = pack_frame(
            pixmap.toImage(),
            invert=bool(self.cfg.get("invert", False)),
            muc_net=int(self.cfg.get("muc_net", MUC_NET_CAN_BANG)),
        )
        self._so_khung_chup += 1
        if khung != self._khung_preview:
            self._khung_preview = khung
            self.preview_canvas.update_frame(khung)

        # Lọc khung trùng + giữ sống 2 giây do kết nối tự làm
        if self._is_streaming and self.ket_noi is not None:
            self.ket_noi.gui_khung(khung)

    def _cap_nhat_thong_ke(self):
        bay_gio = time.monotonic()
        troi = bay_gio - self._luc_do_fps
        if troi > 0:
            self._fps_chup = self._so_khung_chup / troi
        self._so_khung_chup = 0
        self._luc_do_fps = bay_gio

        tk = self.ket_noi.lay_thong_ke() if self.ket_noi is not None else {}
        so_khung = tk.get("so_khung_da_gui", 0)
        kb = tk.get("so_byte_da_gui", 0) / 1024
        dung_luong = f"{kb / 1024:.1f} MB" if kb >= 1024 else f"{kb:.0f} KB"
        nhip = f"{self._fps_chup:.1f} fps" if self._is_streaming else "Chưa chia sẻ"

        ds = tk.get("ds_thiet_bi", [])
        if ds:
            ip_str = ", ".join(ds)
            self.lbl_thiet_bi.setText(f"Thiết bị: <b style='color:#16a34a'>{ip_str}</b>")
            dev_str = f"  |  Thiết bị: <b style='color:#16a34a'>{ip_str}</b>"
        else:
            self.lbl_thiet_bi.setText("Thiết bị: <span style='color:#94a3b8'>chưa kết nối</span>")
            dev_str = ""

        # Ghi log khi thiết bị kết nối mới hoặc ngắt
        tap_ds = set(ds)
        moi = tap_ds - self._ds_thiet_bi_truoc
        ngat = self._ds_thiet_bi_truoc - tap_ds
        for ip in moi:
            self._ghi_log(f"Thiết bị đã kết nối: {ip}")
        for ip in ngat:
            self._ghi_log(f"Thiết bị đã ngắt: {ip}")
        self._ds_thiet_bi_truoc = tap_ds

        self.lbl_stats.setText(f"{nhip}  |  Đã gửi: {so_khung} khung ({dung_luong}){dev_str}  |  2-bit")

    # ================= KẾT NỐI =================

    def _tao_relay(self) -> RelayClient:
        rc = RelayClient(
            server_url=self.cfg.get("server_url", "http://localhost:8000"),
            device_id=self.cfg.get("device_id", "test"),
            api_key=self.cfg.get("api_key", ""),
            username=self.cfg.get("username", "admin"),
            password=self.cfg.get("password", ""),
            token=self.cfg.get("token", ""),
            ws_path=self.cfg.get("ws_path", "/ws/remote"),
            khi_co_lenh=self.lenh_tu_esp32.emit,
            khi_co_token_moi=self.token_moi.emit,
        )
        rc.khi_doi_trang_thai = lambda ma, mo_ta, nguon=rc: self.trang_thai_relay.emit(nguon, ma, mo_ta)
        return rc

    def _khoi_dong_ket_noi(self):
        if self.ket_noi is not None:
            self.ket_noi.dung()
            self.ket_noi = None

        if self._la_noi_thang():
            sv = DirectServer(
                port=self.cfg.get("direct_port", 8268),
                ws_path=self.cfg.get("ws_path", "/ws/remote"),
                khi_co_lenh=self.lenh_tu_esp32.emit,
            )
            sv.khi_doi_trang_thai = lambda ma, mo_ta, nguon=sv: self.trang_thai_relay.emit(nguon, ma, mo_ta)
            self.ket_noi = sv
            sv.bat_dau()
            sv.dat_chia_se(self._is_streaming)
        else:
            self._hien_trang_thai("", "chưa chia sẻ - bấm Bắt đầu để vào server")
            self._ap_dung_chia_se()
        self._cap_nhat_dia_chi()

    def _mo_cau_hinh_relay(self):
        _ghi_cau_hinh_remote(self.cfg)      # hộp thoại đọc lại từ file
        dlg = HopThoaiCauHinhRelay(relay_client=self.ket_noi, parent=self)
        dlg.ket_noi_thanh_cong.connect(self._ap_dung_cau_hinh_relay)
        dlg.exec()

    def _ap_dung_cau_hinh_relay(self, new_cfg: dict):
        self.cfg.update(new_cfg)
        _ghi_cau_hinh_remote(self.cfg)
        self._khoi_dong_ket_noi()

    def _xu_ly_trang_thai_relay(self, nguon, trang_thai: str, mo_ta: str):
        if nguon is not self.ket_noi:
            return                          # tin muộn của kết nối đã đóng
        self._hien_trang_thai(trang_thai, mo_ta)
        self._cap_nhat_thong_ke()
        if trang_thai == "loi" and self._is_streaming:
            # Bị thay thế / bị khoá / sai tài khoản / cổng bận: dừng, giữ thông báo lỗi
            self._dat_chia_se(False, bao_trang_thai=False)

    # ================= XỬ LÝ LỆNH =================

    @staticmethod
    def _doc_xy(tham_so: str):
        a, b = tham_so.split(",")[:2]
        return int(a.strip()), int(b.strip())

    def _vung_hien_tai(self) -> QRect:
        return QRect(self.spin_x.value(), self.spin_y.value(), self.spin_w.value(), self.spin_h.value())

    def _xu_ly_lenh_esp32(self, lenh: str):
        """Thực thi lệnh điều khiển gửi lên. Chạy ở luồng Qt (đã qua signal) nên
        không chặn luồng mạng; mỗi lệnh chỉ là 1 lần SendInput, không ngủ."""
        if not lenh:
            return
        ma, tham_so = lenh[0], lenh[1:]
        try:
            ket_qua = self._thuc_hien_lenh(ma, tham_so)
        except Exception as e:
            ket_qua = f"lỗi: {e}"
        hien = lenh if len(lenh) <= 60 else lenh[:57] + "..."
        self._ghi_log(f"{hien}  ->  {ket_qua}")

    def _thuc_hien_lenh(self, ma: str, tham_so: str) -> str:
        vung = self._vung_hien_tai()

        # C<x>,<y> click trái / P<x>,<y> click phải. Quy đổi giống app
        # Android: chia 192 và 62 (REMOTE_PC_ANDROID.md mục 5.3).
        if ma in ("C", "P"):
            lx, ly = self._doc_xy(tham_so)
            px = vung.x() + int(lx * vung.width() / 192)
            py = vung.y() + int(ly * vung.height() / 62)
            QCursor.setPos(px, py)          # toạ độ Qt: tự quy đổi khi Windows phóng 125%/150%
            dk.bam_chuot(phai=(ma == "P"))
            return f"{'click phải' if ma == 'P' else 'click'} tại ({px}, {py})"

        # S<delta>: S60 = cuộn XUỐNG 1 nấc ở 1.0x (Page Down).
        # Lăn chuột Windows giá trị dương là lăn tới = trang đi LÊN, nên đổi dấu.
        if ma == "S":
            delta = int(tham_so)
            toc_do = float(self.cfg.get("toc_do_cuon", 1.0))
            if not vung.contains(QCursor.pos()):
                QCursor.setPos(vung.center())   # cuộn đúng nội dung đang chia sẻ (Android: vuốt giữa vùng)
            dk.lan_chuot(-round(delta * 2 * toc_do))
            return f"cuộn {'xuống' if delta > 0 else 'lên'} (x{toc_do:g})"

        # V<mức> (V0.5/V1.0/V1.5/V2.0) hoặc V+/V-: hệ số cho các S sau
        if ma == "V":
            tham_so = tham_so.strip()
            if tham_so in ("+", "-"):
                v = _nac_ke_tiep(MUC_TOC_DO_CUON, float(self.cfg.get("toc_do_cuon", 1.0)), tham_so)
            else:
                v = float(tham_so)
            v = max(0.25, min(v, 4.0))
            self.cfg["toc_do_cuon"] = v
            self._chon_toc_do_cuon(v)
            self._hen_luu()
            return f"tốc độ cuộn {v:g}x"

        if ma == "T":
            dk.go_chu(tham_so)
            return f"gõ {len(tham_so)} ký tự"

        # M<hx>,<hy>: dời vùng chụp theo HƯỚNG (-1/0/1). thiết bị không biết vùng
        # thật rộng bao nhiêu nên PC tự tính: mỗi lần dời pan_ty_le kích thước
        # vùng (mặc định 50% - phần chồng giữ mốc nhìn), rồi gửi khung ngay.
        if ma == "M":
            hx, hy = self._doc_xy(tham_so)
            hx, hy = max(-1, min(hx, 1)), max(-1, min(hy, 1))
            ty_le = max(0.05, min(float(self.cfg.get("pan_ty_le", 0.5)), 1.0))
            buoc_x = max(1, int(vung.width() * ty_le))
            buoc_y = max(1, int(vung.height() * ty_le))
            self._dat_vung(vung.x() + hx * buoc_x, vung.y() + hy * buoc_y, vung.width(), vung.height())
            return f"dời vùng tới ({self.spin_x.value()}, {self.spin_y.value()})"

        # Z<mức> (Z1.0/Z1.5/Z2.0/Z3.0) hoặc Z+/Z-
        if ma == "Z":
            return self._dat_zoom(tham_so)

        # K<phím>: ENTER, ESC, TAB, SPACE, BACKSPACE, mũi tên, HOMESCREEN (Win), RECENTS (Win+Tab)
        if ma == "K":
            return f"phím {tham_so.strip().upper()}" if dk.bam_phim(tham_so) else "phím lạ, bỏ qua"

        return "lệnh lạ, bỏ qua"

    def closeEvent(self, event):
        self.stream_timer.stop()
        self.fps_timer.stop()
        self.luu_timer.stop()
        _ghi_cau_hinh_remote(self.cfg)
        if self.ket_noi is not None:
            self.ket_noi.dung()
        event.accept()


def is_admin() -> bool:
    """Kiểm tra quyền Administrator."""
    try:
        return ctypes.windll.shell32.IsUserAnAdmin() != 0
    except Exception:
        return False


def yeu_cau_quyen_admin():
    """Tự động kích hoạt hộp thoại UAC để yêu cầu quyền Administrator trên Windows."""
    if is_admin() or "--no-admin" in sys.argv:
        return
    try:
        # Gom các tham số và gọi ShellExecuteW với động từ 'runas'
        params = " ".join([f'"{a}"' for a in sys.argv])
        ret = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1)
        if ret > 32:
            sys.exit(0)
    except Exception:
        pass


def main():
    yeu_cau_quyen_admin()
    app = QApplication(sys.argv)
    win = RemotePCWindow()
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()