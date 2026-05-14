import struct
import csv
import os
import tkinter as tk
from tkinter import filedialog

# ---------------- SELECCIÓN DE ARCHIVO ----------------

root = tk.Tk()
root.withdraw()  # oculta ventana principal

bin_path = filedialog.askopenfilename(
    title="Wählen Sie die BIN-Datei aus",
    filetypes=[("BIN files", "*.BIN"), ("All files", "*.*")]
)

if not bin_path:
    print("Es wurde keine Datei ausgewählt.")
    exit()

csv_path = os.path.splitext(bin_path)[0] + ".csv"

# ---------------- ESTRUCTURA BIN ----------------

STRUCT_FMT = "<Ihhhhhhh"   # uint32 + 7 int16
STRUCT_SIZE = struct.calcsize(STRUCT_FMT)

# ---------------- CONVERSIÓN --------------------

with open(bin_path, "rb") as f, open(csv_path, "w", newline="") as csvfile:
    writer = csv.writer(csvfile)

    writer.writerow([
        "Time_s",
        "AccX_raw", "AccY_raw", "AccZ_raw",
        "GyroX_raw", "GyroY_raw", "GyroZ_raw",
        "Temp_C"
    ])

    t0_ms = None

    while True:
        data = f.read(STRUCT_SIZE)
        if len(data) != STRUCT_SIZE:
            break

        t_ms, ax, ay, az, gx, gy, gz, temp = struct.unpack(STRUCT_FMT, data)

        # Establecer tiempo cero con la primera muestra
        if t0_ms is None:
            t0_ms = t_ms

        t_s = (t_ms - t0_ms) * 1e-3
        temp_c = temp / 100.0

        writer.writerow([
            f"{t_s:.6f}",
            ax, ay, az,
            gx, gy, gz,
            f"{temp_c:.2f}"
        ])

print(f"Vollstaendige Dateikonvertierung:\n{csv_path}")
