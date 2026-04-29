package com.example.lnbitstaptopay.printing

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.IOException
import java.util.UUID

class BluetoothPrinterTransport(
    context: Context,
    private val configRepository: PrinterConfigRepository = PrinterConfigRepository(context)
) {
    data class BondedPrinter(
        val name: String,
        val address: String
    )

    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun bondedPrinters(): List<BondedPrinter> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return adapter.bondedDevices
            .orEmpty()
            .map { BondedPrinter(it.name ?: "Unnamed printer", it.address ?: "") }
            .filter { it.address.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }

    fun savedPrinter(): BondedPrinter? {
        val config = configRepository.getPrinterConfig() ?: return null
        return BondedPrinter(
            name = config.name.ifBlank { "Saved printer" },
            address = config.address
        )
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun print(bytes: ByteArray) {
        val config = configRepository.getPrinterConfig()
            ?: throw IOException("No Bluetooth printer configured")
        val adapter = bluetoothAdapter ?: throw IOException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val device = adapter.getRemoteDevice(config.address)
        writeToDevice(device, bytes)
    }

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    private fun writeToDevice(device: BluetoothDevice, bytes: ByteArray) {
        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            socket.connect()
            socket.outputStream.use { out ->
                out.write(bytes)
                out.flush()
            }
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
