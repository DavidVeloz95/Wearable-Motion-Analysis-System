import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path
from tkinter import filedialog
import tkinter as tk

# ==================================================
# CONSTANTES DEL SENSOR (LSM6DS3)
# ==================================================
ACC_SCALE = 0.000061   # g / LSB  (±2g)
GYRO_SCALE = 0.00875   # °/s / LSB (±250 dps)

# ==================================================
# SELECCIÓN DE ARCHIVO
# ==================================================
root = tk.Tk()
root.withdraw()

csv_path = filedialog.askopenfilename(
    title="Seleccione el archivo CSV",
    filetypes=[("CSV files", "*.csv")]
)

if not csv_path:
    print("No se seleccionó ningún archivo.")
    exit()

# ==================================================
# LECTURA DEL CSV
# ==================================================
df = pd.read_csv(csv_path, encoding="latin1")
df = df.loc[:, ~df.columns.str.contains("Unnamed")]
df.columns = df.columns.str.strip()

# ==================================================
# CONVERSIÓN DE TIEMPO → SEGUNDOS (MANEJA ROLLOVER)
# ==================================================
def timestamp_a_segundos(t):
    try:
        return float(t)  # ya está en segundos
    except ValueError:
        pass

    if ":" in str(t):   # formato MM:SS(.s)
        minutos, segundos = str(t).split(":")
        return int(minutos) * 60 + float(segundos)

    raise ValueError(f"Formato de tiempo no soportado: {t}")

tiempo_raw = df["Time_s"].astype(str).apply(timestamp_a_segundos)

tiempo_continuo = []
offset = 0
HORA = 3600

for i in range(len(tiempo_raw)):
    if i > 0 and tiempo_raw.iloc[i] < tiempo_raw.iloc[i - 1]:
        offset += HORA
    tiempo_continuo.append(tiempo_raw.iloc[i] + offset)

df["Time_s"] = tiempo_continuo
df["Time_s"] -= df["Time_s"].iloc[0]

# ==================================================
# CONVERSIÓN A UNIDADES FÍSICAS
# ==================================================
for eje in ["AccX_raw", "AccY_raw", "AccZ_raw"]:
    if eje in df.columns:
        df[eje] = df[eje] * ACC_SCALE

for eje in ["GyroX_raw", "GyroY_raw", "GyroZ_raw"]:
    if eje in df.columns:
        df[eje] = df[eje] * GYRO_SCALE

if "Temp_C" in df.columns:
    df["Temp_C"] = df["Temp_C"] / 100.0

# ==================================================
# CARPETA DE SALIDA
# ==================================================
ruta_csv = Path(csv_path)
carpeta_salida = ruta_csv.parent

# ==================================================
# GRAFICAR ACELERÓMETRO
# ==================================================
plt.figure()
plt.plot(df["Time_s"], df["AccX_raw"], label="AX (g)")
plt.plot(df["Time_s"], df["AccY_raw"], label="AY (g)")
plt.plot(df["Time_s"], df["AccZ_raw"], label="AZ (g)")
plt.xlabel("Zeit [s]")
plt.ylabel("Beschleunigung [g]")
plt.title("Beschleunigungsmesser")
plt.legend()
plt.grid(True)
plt.savefig(carpeta_salida / "acelerometro.png", dpi=300)
plt.show()

# ==================================================
# GRAFICAR GIROSCOPIO
# ==================================================
plt.figure()
plt.plot(df["Time_s"], df["GyroX_raw"], label="GX (°/s)")
plt.plot(df["Time_s"], df["GyroY_raw"], label="GY (°/s)")
plt.plot(df["Time_s"], df["GyroZ_raw"], label="GZ (°/s)")
plt.xlabel("Zeit [s]")
plt.ylabel("Winkelgeschwindigkeit [°/s]")
plt.title("Gyroskop")
plt.legend()
plt.grid(True)
plt.savefig(carpeta_salida / "giroscopio.png", dpi=300)
plt.show()

# ==================================================
# GRAFICAR TEMPERATURA
# ==================================================
if "Temp" in df.columns:
    plt.figure()
    plt.plot(df["Time_s"], df["Temp_C"], label="Temperatur")
    plt.xlabel("Zeit [s]")
    plt.ylabel("Temperatur [°C]")
    plt.title("Temperatur")
    plt.legend()
    plt.grid(True)
    plt.savefig(carpeta_salida / "temperatura.png", dpi=300)
    plt.show()
