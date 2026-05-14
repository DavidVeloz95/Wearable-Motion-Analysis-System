import sys
import asyncio
import threading
from collections import deque
import struct
import csv
from datetime import datetime

from bleak import BleakClient
import pyqtgraph as pg
from PyQt5 import QtWidgets, QtCore
from PyQt5 import QtGui


# =========================
# BLE Eigenschaften
# =========================
DEVICE_ADDRESS = "D3:F0:AC:3C:D8:37"
UART_RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # write
UART_TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # notify
BUFFER_LEN = 500

# =========================
# Kreis Puffern
# =========================
t_buf  = deque(maxlen=BUFFER_LEN)
ax_buf = deque(maxlen=BUFFER_LEN)
ay_buf = deque(maxlen=BUFFER_LEN)
az_buf = deque(maxlen=BUFFER_LEN)
gx_buf = deque(maxlen=BUFFER_LEN)
gy_buf = deque(maxlen=BUFFER_LEN)
gz_buf = deque(maxlen=BUFFER_LEN)
temp_buf = deque(maxlen=BUFFER_LEN)

IMU_STRUCT = struct.Struct("<Ihhhhhhh")  # 18 bytes
rx_buffer = bytearray()

# =========================
# LSM6DS3 Wandlung
# =========================
ACC_SCALE = 16384.0  # ±2g → raw/16384 = g
GYRO_SCALE = 131.0   # ±250 dps → raw/131 = °/s

# =========================
# Gesamtflagge und CSV
# =========================
is_running = True  # solo guarda cuando True
session_time = datetime.now().strftime("%Y%m%d_%H%M%S")
csv_file = open(f"imu_data_{session_time}.csv", mode="w", newline="")
csv_writer = csv.writer(csv_file)
csv_writer.writerow(["Timestamp", "AX(g)", "AY(g)", "AZ(g)", "GX(°/s)", "GY(°/s)", "GZ(°/s)", "Temp(°C)"])

# =========================
# Packet parsing
# =========================
def parse_packet(data: bytearray):
    global rx_buffer, is_running
    rx_buffer.extend(data)

    while len(rx_buffer) >= 20:
        if rx_buffer[0] != 0xA5:
            rx_buffer.pop(0)
            continue

        packet = rx_buffer[:20]
        del rx_buffer[:20]

        payload = packet[2:20]
        t, ax, ay, az, gx, gy, gz, temp = IMU_STRUCT.unpack(payload)

        # Umwandlung
        ax_g = ax / ACC_SCALE
        ay_g = ay / ACC_SCALE
        az_g = az / ACC_SCALE
        gx_dps = gx / GYRO_SCALE
        gy_dps = gy / GYRO_SCALE
        gz_dps = gz / GYRO_SCALE
        temp_c = temp / 100.0

        # In Puffer speichern
        t_buf.append(t / 1000.0)
        ax_buf.append(ax_g)
        ay_buf.append(ay_g)
        az_buf.append(az_g)
        gx_buf.append(gx_dps)
        gy_buf.append(gy_dps)
        gz_buf.append(gz_dps)
        temp_buf.append(temp_c)

        # In CSV nur wenn START speichern
        if is_running:
            timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
            csv_writer.writerow([timestamp, ax_g, ay_g, az_g, gx_dps, gy_dps, gz_dps, temp_c])

# =========================
# Callback BLE
# =========================
def notification_handler(sender, data: bytearray):
    parse_packet(data)

# =========================
# Client BLE global
# =========================
ble_client = None
ble_loop = asyncio.new_event_loop()

async def ble_task():
    global ble_client
    async with BleakClient(DEVICE_ADDRESS, loop=ble_loop) as client:
        ble_client = client
        if not client.is_connected:
            print("Verbindung unmoeglich")
            return

        print("Verbindet mit XIAO BLE")
        await client.start_notify(UART_TX_UUID, notification_handler)
        await client.write_gatt_char(UART_RX_UUID, b"START")
        print("START gesendet")

        try:
            while True:
                await asyncio.sleep(0.01)
        except asyncio.CancelledError:
            pass
        finally:
            await client.write_gatt_char(UART_RX_UUID, b"STOP")
            await client.stop_notify(UART_TX_UUID)
            print("STOP gesendet und erfassung gestopt")

def start_ble_loop():
    asyncio.set_event_loop(ble_loop)
    ble_loop.run_until_complete(ble_task())

# =========================
# Taste START/STOP
# =========================
def toggle_start_stop():
    global is_running
    if ble_client is None:
        return
    if is_running:
        asyncio.run_coroutine_threadsafe(
            ble_client.write_gatt_char(UART_RX_UUID, b"STOP"), ble_loop
        )
        btn_start_stop.setText("START")
        is_running = False
    else:
        asyncio.run_coroutine_threadsafe(
            ble_client.write_gatt_char(UART_RX_UUID, b"START"), ble_loop
        )
        btn_start_stop.setText("STOP")
        is_running = True

# =========================
# GUI
# =========================
app = QtWidgets.QApplication([])

window = QtWidgets.QWidget()
window.setWindowTitle("XIAO BLE IMU")
window.resize(1200, 800)
main_layout = QtWidgets.QHBoxLayout(window)

# Panel links: Taste und Werte
left_panel = QtWidgets.QWidget()
left_layout = QtWidgets.QVBoxLayout(left_panel)
left_panel.setSizePolicy(
    QtWidgets.QSizePolicy.Fixed,
    QtWidgets.QSizePolicy.Expanding
)
left_panel.setFixedWidth(220)   # ajusta a gusto
main_layout.addWidget(left_panel)

btn_start_stop = QtWidgets.QPushButton("STOP")
btn_start_stop.setFixedSize(100, 40)
btn_start_stop.clicked.connect(toggle_start_stop)
left_layout.addWidget(btn_start_stop)

label_acc = QtWidgets.QLabel("AX: 0.00 g\nAY: 0.00 g\nAZ: 0.00 g")
label_acc.setStyleSheet("font-size: 28px; font-weight: bold;")
label_gyro = QtWidgets.QLabel("GX: 0.0 °/s\nGY: 0.0 °/s\nGZ: 0.0 °/s")
label_gyro.setStyleSheet("font-size: 28px; font-weight: bold;")
label_temp = QtWidgets.QLabel("T: 0.0 °C")
label_temp.setStyleSheet("font-size: 28px; font-weight: bold;")

left_layout.addWidget(label_acc)
left_layout.addWidget(label_gyro)
left_layout.addWidget(label_temp)
left_layout.addStretch()

# Panel rechts: Charts
right_panel = pg.GraphicsLayoutWidget()
main_layout.addWidget(right_panel, 3)



p1 = right_panel.addPlot(title="Beschleunigungsmesser (g)", size="20pt")
curve_ax = p1.plot(pen=pg.mkPen('r', width=2))
curve_ay = p1.plot(pen=pg.mkPen('g', width=2))
curve_az = p1.plot(pen=pg.mkPen('b', width=2))
p1.addLegend()
p1.showGrid(x=True, y=True, alpha=0.3)
right_panel.nextRow()

p2 = right_panel.addPlot(title="Gyroskop (°/s)", size="20pt")
curve_gx = p2.plot(pen='r')
curve_gy = p2.plot(pen='g')
curve_gz = p2.plot(pen='b')
p2.addLegend()
p2.showGrid(x=True, y=True, alpha=0.3)
right_panel.nextRow()

p3 = right_panel.addPlot(title="Temperatur (°C)", size="20pt")
curve_temp = p3.plot(pen='y')
p3.addLegend()
p3.showGrid(x=True, y=True, alpha=0.3)

axis_font = QtGui.QFont()
axis_font.setPointSize(12)

for p in [p1, p2, p3]:
    p.getAxis("left").setTickFont(axis_font)
    p.getAxis("bottom").setTickFont(axis_font)

p1.legend.setLabelTextSize("12pt")
p2.legend.setLabelTextSize("12pt")
p3.legend.setLabelTextSize("12pt")

# =========================
# Timer zur aktualisierung der Charts und Labels
# =========================
def update():
    if len(t_buf) < 2:
        return
    t = list(t_buf)

    curve_ax.setData(t, list(ax_buf))
    curve_ay.setData(t, list(ay_buf))
    curve_az.setData(t, list(az_buf))

    curve_gx.setData(t, list(gx_buf))
    curve_gy.setData(t, list(gy_buf))
    curve_gz.setData(t, list(gz_buf))

    curve_temp.setData(t, list(temp_buf))

    label_acc.setText(
        f"AX: {ax_buf[-1]:.2f} g\nAY: {ay_buf[-1]:.2f} g\nAZ: {az_buf[-1]:.2f} g"
    )
    label_gyro.setText(
        f"GX: {gx_buf[-1]:.1f} °/s\nGY: {gy_buf[-1]:.1f} °/s\nGZ: {gz_buf[-1]:.1f} °/s"
    )
    label_temp.setText(f"T: {temp_buf[-1]:.2f} °C")

timer = QtCore.QTimer()
timer.timeout.connect(update)
timer.start(20)

# =========================
# BLE starten
# =========================
ble_thread = threading.Thread(target=start_ble_loop, daemon=True)
ble_thread.start()

# =========================
# GUI execute
# =========================
window.show()
if __name__ == "__main__":
    QtWidgets.QApplication.instance().exec_()

# =========================
# CSV schliessen 
# =========================
csv_file.close()
