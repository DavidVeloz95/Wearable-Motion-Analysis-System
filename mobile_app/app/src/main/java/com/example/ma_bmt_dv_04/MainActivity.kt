package com.example.ma_bmt_dv_04

import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color
import android.widget.RadioGroup
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.Entry

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    )

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var nusManager: NUSManager
    private var isScanning = false
    private var isStreaming = false
    private val ACCEL_SENS = 0.000061f   // g / LSB
    private val GYRO_SENS = 0.00875f    // dps / LSB

    private lateinit var accelChart: LineChart
    private val bufferSize = 200

    private val accelX = ArrayDeque<Float>(bufferSize)
    private val accelY = ArrayDeque<Float>(bufferSize)
    private val accelZ = ArrayDeque<Float>(bufferSize)

    private val gyroX = ArrayDeque<Float>(bufferSize)
    private val gyroY = ArrayDeque<Float>(bufferSize)
    private val gyroZ = ArrayDeque<Float>(bufferSize)

    private enum class SensorMode { ACCEL, GYRO }
    private var currentSensorMode = SensorMode.ACCEL

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()

        // --- Inicializar UI ---
        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnDisconnect = findViewById<Button>(R.id.btnDisconnect)
        val btnStartStreaming = findViewById<Button>(R.id.btnStartStreaming)
        val sensorSelector = findViewById<RadioGroup>(R.id.sensorSelector)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        accelChart = findViewById(R.id.accelChart)

        btnDisconnect.isEnabled = false
        btnStartStreaming.isEnabled = false

        setupAccelChart()

        // --- Listener del selector: SOLO cambia modo ---
        sensorSelector.setOnCheckedChangeListener { _, checkedId ->
            currentSensorMode = when (checkedId) {
                R.id.rbAccel -> SensorMode.ACCEL
                R.id.rbGyro -> SensorMode.GYRO
                // R.id.rbBoth -> SensorMode.BOTH
                else -> SensorMode.ACCEL
            }
            updateYAxisForMode()
            accelChart.data = LineData()
            accelChart.invalidate()
        }

        // --- Inicializar BLE ---
        nusManager = NUSManager(
            context = this,
            onImuSample = { s ->
                if (!isStreaming) return@NUSManager
                val ax_g = s.ax * ACCEL_SENS
                val ay_g = s.ay * ACCEL_SENS
                val az_g = s.az * ACCEL_SENS
                val gx_dps = s.gx * GYRO_SENS
                val gy_dps = s.gy * GYRO_SENS
                val gz_dps = s.gz * GYRO_SENS

                runOnUiThread {
                    // Actualizar TextViews
                    findViewById<TextView>(R.id.tvAccelX).text = "%.3f".format(ax_g)
                    findViewById<TextView>(R.id.tvAccelY).text = "%.3f".format(ay_g)
                    findViewById<TextView>(R.id.tvAccelZ).text = "%.3f".format(az_g)
                    findViewById<TextView>(R.id.tvGyroX).text = "%.2f".format(gx_dps)
                    findViewById<TextView>(R.id.tvGyroY).text = "%.2f".format(gy_dps)
                    findViewById<TextView>(R.id.tvGyroZ).text = "%.2f".format(gz_dps)

                    // Actualizar buffers circulares
                    if (accelX.size >= bufferSize) { accelX.removeFirst(); accelY.removeFirst(); accelZ.removeFirst() }
                    accelX.addLast(ax_g); accelY.addLast(ay_g); accelZ.addLast(az_g)

                    if (gyroX.size >= bufferSize) { gyroX.removeFirst(); gyroY.removeFirst(); gyroZ.removeFirst() }
                    gyroX.addLast(gx_dps); gyroY.addLast(gy_dps); gyroZ.addLast(gz_dps)

                    // Actualizar gráfica según el modo seleccionado
                    when (currentSensorMode) {
                        SensorMode.ACCEL -> updateChart(axBuffer = accelX,  ayBuffer = accelY, azBuffer = accelZ)
                        SensorMode.GYRO -> updateChart(gxBuffer = gyroX, gyBuffer = gyroY, gzBuffer = gyroZ)
                        //SensorMode.BOTH -> updateChart(axBuffer = accelX, ayBuffer = accelY, azBuffer = accelZ, gxBuffer = gyroX, gyBuffer = gyroY, gzBuffer = gyroZ)
                    }
                }
            },
            onConnected = { connected ->
                runOnUiThread {
                    tvStatus.text = if (connected) "Verbunden mit Prototyp" else "Keine Verbindung"
                    if (connected) {
                        btnScan.text = "Scannen"
                        nusManager.sendText("START\n")
                        btnDisconnect.text = "Trennen"
                        btnScan.isEnabled = false
                        btnStartStreaming.isEnabled = true
                        btnDisconnect.isEnabled = true
                        isScanning = false
                        isStreaming = true
                        btnStartStreaming.text = "Streaming beenden"
                    } else {
                        btnScan.text = "Scannen"
                        btnScan.isEnabled = true
                        btnDisconnect.text = "Trennen"
                        btnDisconnect.isEnabled = false
                        btnStartStreaming.isEnabled = false
                    }
                }
            }
        )

        // --- Configurar botones ---
        btnScan.setOnClickListener {
            if (!isScanning) {
                nusManager.startScan()
                isScanning = true
                btnScan.text = "Stop"
                btnDisconnect.isEnabled = false
            } else {
                nusManager.stopScan()
                isScanning = false
                btnScan.text = "Scannen"
            }
        }

        btnDisconnect.setOnClickListener {
            if (btnDisconnect.text == "Trennen") {
                nusManager.disconnect()
                btnStartStreaming.isEnabled = false
                tvStatus.text = "Keine Verbindung"
                btnDisconnect.text = "Verbinden"
                btnDisconnect.isEnabled = false
                btnScan.isEnabled = true
            } else {
                nusManager.startScan()
                btnScan.text = "Stop"
                btnScan.isEnabled = true
                btnDisconnect.isEnabled = false
                btnStartStreaming.isEnabled = false
                isScanning = true
            }
        }

        btnStartStreaming.setOnClickListener {
            if (!isStreaming) {
                nusManager.sendText("START\n")
                isStreaming = true
                btnStartStreaming.text = "Streaming beenden"
            } else {
                nusManager.sendText("STOP\n")
                isStreaming = false
                btnStartStreaming.text = "Streaming starten"
            }
        }

        // --- Solicitar permisos si no los tenemos ---
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val granted = perms.values.all { it }
            if (granted) {
                nusManager.startScan()
                isScanning = true
                btnScan.text = "Stop"
            } else {
                btnScan.text = "Scannen"
            }
        }

        if (hasAllPermissions()) {
            nusManager.startScan()
            isScanning = true
            btnScan.text = "Stop"
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    // --- Funciones de gráfica ---
    private fun createDataSet(label: String, color: Int, buffer: ArrayDeque<Float>): LineDataSet {
        val entries = buffer.mapIndexed { i, v -> Entry(i.toFloat(), v) }
        return LineDataSet(entries, label).apply {
            this.color = color
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            mode = LineDataSet.Mode.LINEAR
        }
    }

    private fun updateChart(
        axBuffer: ArrayDeque<Float>? = null,
        ayBuffer: ArrayDeque<Float>? = null,
        azBuffer: ArrayDeque<Float>? = null,
        gxBuffer: ArrayDeque<Float>? = null,
        gyBuffer: ArrayDeque<Float>? = null,
        gzBuffer: ArrayDeque<Float>? = null
    ) {
        val data = accelChart.data ?: LineData().also { accelChart.data = it }
        data.clearValues()

        //if (currentSensorMode == SensorMode.ACCEL || currentSensorMode == SensorMode.BOTH) {
        if (currentSensorMode == SensorMode.ACCEL) {
            axBuffer?.let { data.addDataSet(createDataSet("Accel X", Color.RED, it)) }
            ayBuffer?.let { data.addDataSet(createDataSet("Accel Y", Color.GREEN, it)) }
            azBuffer?.let { data.addDataSet(createDataSet("Accel Z", Color.BLUE, it)) }
        }

        //if (currentSensorMode == SensorMode.GYRO || currentSensorMode == SensorMode.BOTH) {
        if (currentSensorMode == SensorMode.GYRO) {
            gxBuffer?.let { data.addDataSet(createDataSet("Gyro X", Color.MAGENTA, it)) }
            gyBuffer?.let { data.addDataSet(createDataSet("Gyro Y", Color.CYAN, it)) }
            gzBuffer?.let { data.addDataSet(createDataSet("Gyro Z", Color.YELLOW, it)) }
        }

        data.notifyDataChanged()
        accelChart.notifyDataSetChanged()
        accelChart.invalidate()
    }

    private fun setupAccelChart() {
        accelChart.description.isEnabled = false
        accelChart.setTouchEnabled(true)
        accelChart.setPinchZoom(true)
        accelChart.setScaleEnabled(true)
        accelChart.setScaleXEnabled(true)
        accelChart.setScaleYEnabled(true)
        accelChart.setDoubleTapToZoomEnabled(true)
        accelChart.axisRight.isEnabled = false
        accelChart.xAxis.isEnabled = false
        accelChart.axisLeft.axisMinimum = -3f
        accelChart.axisLeft.axisMaximum = 3f
        accelChart.legend.isEnabled = true
        accelChart.data = LineData()
    }

    private fun updateYAxisForMode() {
        when (currentSensorMode) {
            SensorMode.ACCEL -> {
                accelChart.axisLeft.axisMinimum = -3f
                accelChart.axisLeft.axisMaximum = 3f
            }
            SensorMode.GYRO -> {
                accelChart.axisLeft.axisMinimum = -300f
                accelChart.axisLeft.axisMaximum = 300f
            }
            //SensorMode.BOTH -> {
                //accelChart.axisLeft.axisMinimum = -300f
                //accelChart.axisLeft.axisMaximum = 300f
            //}
        }
    }
}
