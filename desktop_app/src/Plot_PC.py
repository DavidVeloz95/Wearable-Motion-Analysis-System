import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path
from tkinter import filedialog
import tkinter as tk

# ---------------- SELECCIÓN DE ARCHIVO ----------------

root = tk.Tk()
root.withdraw()

csv_path = filedialog.askopenfilename(
    title="Seleccione el archivo CSV",
    filetypes=[("CSV files", "*.csv"), ("All files", "*.*")]
)

if not csv_path:
    print("No se seleccionó ningún archivo.")
    exit()

# ---------------- LECTURA DEL CSV ----------------

df = pd.read_csv(csv_path, encoding="latin1")

# Eliminar columnas vacías (Unnamed)
df = df.loc[:, ~df.columns.str.contains("Unnamed")]

# ---------------- CONVERSIÓN DE TIEMPO ----------------
# Timestamp formato: MM:SS.s

def timestamp_a_segundos(t):
    minutos, segundos = t.split(":")
    return int(minutos) * 60 + float(segundos)

tiempo_raw = df["Timestamp"].apply(timestamp_a_segundos)

tiempo_continuo = []
offset = 0
hora_segundos = 3600

for i in range(len(tiempo_raw)):
    if i > 0 and tiempo_raw.iloc[i] < tiempo_raw.iloc[i - 1]:
        offset += hora_segundos  # cambio de hora detectado
    tiempo_continuo.append(tiempo_raw.iloc[i] + offset)

df["Tiempo_s"] = tiempo_continuo

# Hacer que el tiempo comience en 0
df["Tiempo_s"] -= df["Tiempo_s"].iloc[0]

# Carpeta de salida
ruta_csv = Path(csv_path)
carpeta_salida = ruta_csv.parent

# ================== ACELERÓMETRO ==================
plt.figure()
plt.plot(df["Tiempo_s"], df["AX(g)"], label="AX (g)")
plt.plot(df["Tiempo_s"], df["AY(g)"], label="AY (g)")
plt.plot(df["Tiempo_s"], df["AZ(g)"], label="AZ (g)")
plt.xlabel("Zeit [s]")
plt.ylabel("Beschleunigung [g]")
plt.title("Beschleunigungsmesser")
plt.legend()
plt.grid(True)
plt.savefig(carpeta_salida / "Beschleunigungsmesser.png", dpi=300)
plt.show()

# ================== GIROSCOPIO ==================
plt.figure()
plt.plot(df["Tiempo_s"], df["GX(°/s)"], label="GX (°/s)")
plt.plot(df["Tiempo_s"], df["GY(°/s)"], label="GY (°/s)")
plt.plot(df["Tiempo_s"], df["GZ(°/s)"], label="GZ (°/s)")
plt.xlabel("Zeit [s]")
plt.ylabel("Winkelgeschwindigkeit [°/s]")
plt.title("Gyroskop")
plt.legend()
plt.grid(True)
plt.savefig(carpeta_salida / "Gyroskop.png", dpi=300)
plt.show()

# ================== TEMPERATURA ==================
plt.figure()
plt.plot(df["Tiempo_s"], df["Temp(°C)"], label="Temperatur (°C)")
plt.xlabel("Zeit [s]")
plt.ylabel("Temperatur [°C]")
plt.title("Temperatur")
plt.legend()
plt.grid(True)
plt.savefig(carpeta_salida / "Temperatur.png", dpi=300)
plt.show()
