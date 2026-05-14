/*******************************************************************************/
//  Hardware:       XIAO BLE nRF52840
//  IDE:            Arduino 2.3.7
//  Autor:          David Enrique Veloz Renteria
//  Datum:          12.2025
//  Version:        v4.2
//  
//  Beschreibung:
//  - Logging indefinido y robusto
//  - 
//  - 
//  - 
//  - 
//  - 
//  - 

/*******************************************************************************/

/*******************************************************************************/
// PROGRAMMBIBLIOTHEKEN
/*******************************************************************************/

#include <Arduino.h>
#include <SPI.h>
#include <SD.h>
#include <Wire.h>
#include "LSM6DS3.h"
#include <bluefruit.h>
#include <nrfx_wdt.h>

/******************************************************************************/
// DEFINITIONEN
/******************************************************************************/

#define SD_FLUSH_MS         500
#define IMU_PERIOD_MS       10             // 100 Hz
#define IMU_BUFFER_LEN      2048
#define BLE_SEND_MS         10             // 10 Hz
#define DEVICE_NAME         "XIAO_IMU"     // Gerätename
#define LSM6DS3_ODR_104HZ   0x40
#define LSM6DS3_ODR_208HZ   0x50
#define LSM6DS3_ODR_416HZ   0x60

/******************************************************************************/
// DATENSTRUKTUR
/******************************************************************************/

struct __attribute__((packed)) ImuSample {
  uint32_t t_ms;
  int16_t ax, ay, az;
  int16_t gx, gy, gz;
  int16_t temp;
};

/******************************************************************************/
// GLOBALE VARIABLEN
/******************************************************************************/

// ---------- IMU ----------
LSM6DS3 xiao(I2C_MODE, 0x6A);             // LSM6DS3 I2C-Adresse 0x6A
bool imuDetected = false;                 // IMU-Flagge

// ---------- Zeit ----------
uint32_t t0 = 0;                          // Zeit null
bool timeStarted = false;                 // Zeit-Flagge

// ---------- Ringpuffer ----------
ImuSample buffer[IMU_BUFFER_LEN];         // Constructor
volatile uint16_t head = 0;               // Allgemeine Head von Puffer
volatile uint16_t tailSD = 0;             // Tail von microSD
volatile uint16_t tailBLE = 0;            // Tail von BLE-Verbindung

// ---------- SD ----------
File dataFile;                            // Constructor
bool isInserted = false;                  // MicroSDflagge
const int chipSelect = A2;                // SD-Karten Chip-Select Pin für XIAO BLE

// ---------- BLE ----------
BLEUart bleuart;                          // Constructor
bool bleConnected = false;                // Verbindungsflagge
bool bleStreaming = false;                // START-/STOP-Flagge

// ---------- Serial ----------
bool txtTitel = false;                    // Titelflagge

// ---------- Low-Power Komponenten ----------
const int BUTTON_PIN = D1;                // Push Button    D1
const int LED_PIN = D4;                   // LED
bool buttonPressed = false;               // Button-Flagge
uint32_t pressStart = 0;                  // Dauer des Drückens der Push
bool shutdown = true;

// ---------- Debbug ----------
uint32_t maxBufferFill = 0;
uint32_t test;
bool ichi = true;

/******************************************************************************/
// SETUP
/******************************************************************************/

void setup() {
  // ---------- Serial ----------
  Serial.begin(115200);                   // Serial initialisieren

  // Digitale Ein-/Ausgänge
  pinMode(BUTTON_PIN, INPUT_PULLUP);      // Push Button initialisieren
  pinMode(LED_PIN, OUTPUT);               // LED initialisieren
  pinMode (22, OUTPUT);                // Akkuladestrom
  // pinMode(LED_GREEN, OUTPUT);

  // ---------- IMU ----------
  if (xiao.begin() == 0) {                // IMU @ 416Hz
    imuDetected = true;
    xiao.writeRegister(LSM6DS3_ACC_GYRO_CTRL1_XL, LSM6DS3_ODR_416HZ);
    xiao.writeRegister(LSM6DS3_ACC_GYRO_CTRL2_G,  LSM6DS3_ODR_416HZ);
  }

  // ---------- SD ----------
  if (SD.begin(chipSelect)) {             // microSD initialisieren
    isInserted = true;                    // microSD erkannt

    char name[20];                        // Variabel für die Name des Dateis
    uint16_t n = 1;                       // Anzahl der Dataienname
    do {                                  // Suche nach der Name
      snprintf(name, sizeof(name), "LOG%05u.BIN", n++);
    } while (SD.exists(name));

    dataFile = SD.open(name, FILE_WRITE); // Datei Öffnen/Erstellen
  }

  // ---------- BLE ----------
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX); // 247 bits https://www.delasign.com/blog/arduino-bluefruit-nrf52-mtu/
  Bluefruit.Periph.setConnInterval(6, 12);   // 7.5–15 ms (mínimo permitido)

  Bluefruit.begin();
  //Serial.println(Bluefruit.getMaxMtu(BLE_GAP_ROLE_PERIPH));
  Bluefruit.setName(DEVICE_NAME);
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);
  bleuart.begin();
  bleuart.setRxCallback(uart_rx_callback);  // Callback registrieren, um START/STOP zu empfangen
  Bluefruit.Advertising.addService(bleuart);
  Bluefruit.Advertising.start();
}

/******************************************************************************/
// LOOP
/******************************************************************************/

void loop() {
  digitalWrite(22, LOW);
  IMUlesen();
  SDschreiben();
  BLEsenden();
  ULPM();
  StatusSerial();
  heartbeat();

}

/******************************************************************************/
// IMU → BUFFER (PRODUCTOR)
// Never stops
/******************************************************************************/

void IMUlesen() {
  static uint32_t last_ms = 0;
  uint32_t now = millis();
  uint32_t dt = now - last_ms;
  if (dt < IMU_PERIOD_MS) return;
  last_ms = now;
  if (!timeStarted) {
    t0 = now;
    timeStarted = true;
  }
  ImuSample s;
  s.t_ms = now - t0;
  s.ax = xiao.readRawAccelX();
  s.ay = xiao.readRawAccelY();
  s.az = xiao.readRawAccelZ();
  s.gx = xiao.readRawGyroX();
  s.gy = xiao.readRawGyroY();
  s.gz = xiao.readRawGyroZ();
  s.temp = (int16_t)(xiao.readTempC() * 100);
  uint16_t next = (head + 1) % IMU_BUFFER_LEN;
  uint16_t fill;
  if (head >= tailSD) fill = head - tailSD;
  else fill = IMU_BUFFER_LEN - tailSD + head;
  if (fill > maxBufferFill) maxBufferFill = fill;
  // Buffer lleno → descartar dato más viejo
  if (next == tailSD) {
    tailSD = (tailSD + 1) % IMU_BUFFER_LEN;
  }
  buffer[head] = s;
  head = next;
  //if (ichi == true) { Serial.println(test); ichi = false; Serial.println(s.temp);}
}

/******************************************************************************/
// BUFFER → SD (CONSUMIDOR)
// Continuous Logging 
/******************************************************************************/

void SDschreiben() {
  static uint32_t lastFlush = 0;
  if (!isInserted || !dataFile) return;
  uint16_t count = 0;                       // Zähler für von Loop geschriebene Samples
  const uint16_t MAX_WRITE = 32;            // Begrenzung der von Loop geschriebenen Samples
  while (tailSD != head && count < MAX_WRITE) {
    dataFile.write((uint8_t*)&buffer[tailSD], sizeof(ImuSample));
    tailSD = (tailSD + 1) % IMU_BUFFER_LEN;
    count++;
  }
  if (millis() - lastFlush >= SD_FLUSH_MS) {
    dataFile.flush();
    lastFlush = millis();
  }
}

/******************************************************************************/
// BUFFER → BLE (REAL TIME, WITHOUT BACKLOG)
// Skip-to-latest
/******************************************************************************/

void BLEsenden() {
  static uint32_t lastSend = 0;
  if (!bleConnected || !bleStreaming) return;
  if (millis() - lastSend < BLE_SEND_MS) return;
  lastSend = millis();
  if (head == tailBLE) return;
  uint16_t latest = (head + IMU_BUFFER_LEN - 1) % IMU_BUFFER_LEN; // Skip-to-latest
  ImuSample s = buffer[latest];
  tailBLE = head;               // descarta Backlog
  uint8_t packet[20];           // 1B Start + 1B Muestra + 18B payload
  packet[0] = 0xA5;             // START
  packet[1] = 1;                // 1 Muestra
  memcpy(&packet[2], &s, 18);   // Copiar Payload directamente
  bleuart.write(packet, sizeof(packet));
}

/******************************************************************************/
// CALLBACKS BLE
/******************************************************************************/

void connect_callback(uint16_t conn_handle) {
  bleConnected = true;
  bleStreaming = true;
  writeToSerial(F("[BLE]     Verbindet"));
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  writeToSerial("[BLE]     Getrennt\n");
  writeToSerial("[BLE]     Fehler %d - %s\n", reason, bleDisconnectReason(reason));
  bleConnected = false;
}

const char* bleDisconnectReason(uint8_t reason) {   // https://argenox.com/blog/understanding-ble-disconnections
  switch (reason) {
    case 0x08: return "Connection Timeout";
    case 0x13: return "Remote User Terminated Connection";
    case 0x16: return "Connection Terminated by Local Host";
    case 0x19: return "Remote Device Power Off";
    case 0x22: return "Local Host Terminated Connection";
    case 0x3E: return "Connection Failed to be Established";
    default:   return "Unknown reason";
  }
}

void uart_rx_callback(uint16_t conn_handle) {
  char cmd[32] = {0};
  int len = bleuart.read(cmd, sizeof(cmd) - 1);
  writeToSerial("[BLE]     Empfang: %s", cmd);
  if (strncmp(cmd, "START", 5) == 0) {
    bleStreaming = true;
    writeToSerial(F("[STREAM]  Gestartet"));
  }
  else if (strncmp(cmd, "STOP", 4) == 0) {
    bleStreaming = false;
    writeToSerial(F("[STREAM]  Abgeschlossen"));
  }
}

/******************************************************************************/
// SERIAL SCHREIBEN
/******************************************************************************/

void writeToSerial(const char *fmt, ...) {
  if (!Serial) return;
  char buf[256];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buf, sizeof(buf), fmt, args);
  va_end(args);
  Serial.print(buf);
}

void writeToSerial(const __FlashStringHelper *msg) {
  if (!Serial) return;
  Serial.println(msg);
}

/******************************************************************************/
// ULTRA LOW POWER MODE
/******************************************************************************/

void ULPM() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    if (!buttonPressed) {
      buttonPressed = true;
      pressStart = millis();
    }

    if (millis() - pressStart > 3000) {
      enterLowPowerMode();
    }
  } else {
    buttonPressed = false;
  }
}

void enterLowPowerMode() {
  writeToSerial(F(">>> ULTRA LOW POWER MODE AKTIV <<<"));
  buttonPressed = false;
  SDschreiben(); 
  dataFile.flush();                   // speichert alle Daten auf microSD
  dataFile.close();
  for (int i = 0; i < 3; i++) {       // LED blinkt 3-Mal
    digitalWrite(LED_PIN, HIGH);
    // digitalWrite(LED_GREEN, HIGH);
    delay(200);
    // digitalWrite(LED_GREEN, LOW);
    digitalWrite(LED_PIN, LOW);
    delay(200);
  }
  
  while (digitalRead(BUTTON_PIN) == LOW) {
    delay(10);
  }
  writeToSerial(F("SystemOFF..."));
  delay(50);

  // Wake-Up durch Taste (LOW)
  nrf_gpio_cfg_sense_input(BUTTON_PIN,
                            NRF_GPIO_PIN_PULLUP,
                            NRF_GPIO_PIN_SENSE_LOW);
  delay(50);
  while (shutdown == true) {
    if (digitalRead(BUTTON_PIN) == LOW) {
      shutdown = false;
    }
  }
  
  NRF_POWER->SYSTEMOFF = 1;
  
  while (1);
}

/******************************************************************************/
// STATUS
/******************************************************************************/

void StatusSerial() {
  // Status nach Empfang einer Tasteneingabe über die serielle Schnittstelle senden
  if (!txtTitel) {
    if (Serial.available() > 0) {
      Serial.read();  // limpiar el byte recibido
      writeToSerial(F("**Prototypische Entwicklung eines Wearables zur Erfassung kinematischer Daten des Rückens mittels inertialer Messeinheit**"));
      txtTitel = true;
      if (imuDetected) { writeToSerial(F("[IMU]     Sensor OK!")); } else { writeToSerial(F("[IMU]     Sensorfehler!")); }
      // if (isConnected) { writeToSerial(F("[BLE]     Verbindet")); } else { writeToSerial(F("[BLE]     Keine Verbindung")); writeToSerial(F("[BLE]     Bereit und warte auf Pairing!")); }
      if (isInserted) { writeToSerial(F("[microSD] MicroSD gefunden!")); } else { writeToSerial(F("[microSD] Keine MicroSD gefunden!")); }
      // if (dataFile) { writeToSerial(F("[microSD] Datei erstellt und verbendet.")); } else { writeToSerial(F("[microSD] Fehler beim Öffnen bzw. Erstellung der Log-Datei!")); }
    }
  }
}

void heartbeat() {
  static uint32_t last = 0;
  if (millis() - last > 1000) {
    if (isInserted) {
      //digitalWrite(LED_GREEN, !digitalRead(LED_GREEN));
      digitalWrite(LED_PIN, !digitalRead(LED_PIN));
      last = millis();
    }
  }
}
/******************************************************************************/
// ENDE
/******************************************************************************/