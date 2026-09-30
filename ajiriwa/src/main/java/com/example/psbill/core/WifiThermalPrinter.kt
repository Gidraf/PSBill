package com.example.psbill.ui.screens

// Shared ESC/POS network printer helper (used by printing, orders and WiFi vouchers).

import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object WifiThermalPrinter {
    fun printVoucher(
        context: android.content.Context,
        printerIp: String,
        voucherCode: String,
        planTitle: String,
        price: String,
        duration: String,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (printerIp.isBlank()) {
            onResult(false, "Printer IP is not set. Please set IP in Printing tab.")
            return
        }

        Thread {
            try {
                val ESC: Byte = 0x1B
                val GS: Byte = 0x1D
                val LF: Byte = 0x0A

                val bos = java.io.ByteArrayOutputStream()
                bos.write(byteArrayOf(ESC, 0x40)) // ESC @ Init
                bos.write(byteArrayOf(ESC, 0x61, 1)) // ESC a 1 Center

                bos.write(byteArrayOf(ESC, 0x45, 1)) // Bold ON
                bos.write("AJIRIWA WIFI BILLING\n".toByteArray(Charsets.ISO_8859_1))
                bos.write(byteArrayOf(ESC, 0x45, 0)) // Bold OFF
                bos.write("================================\n".toByteArray(Charsets.ISO_8859_1))

                bos.write("Plan: $planTitle\n".toByteArray(Charsets.ISO_8859_1))
                bos.write("Price: $price  |  Duration: $duration\n\n".toByteArray(Charsets.ISO_8859_1))

                bos.write(byteArrayOf(GS, 0x21, 0x11)) // Double height & width
                bos.write(byteArrayOf(ESC, 0x45, 1)) // Bold
                bos.write("CODE: $voucherCode\n".toByteArray(Charsets.ISO_8859_1))
                bos.write(byteArrayOf(GS, 0x21, 0x00)) // Normal
                bos.write(byteArrayOf(ESC, 0x45, 0)) // Bold OFF

                bos.write("\n================================\n".toByteArray(Charsets.ISO_8859_1))
                bos.write("Connect to WiFi & enter voucher code\n".toByteArray(Charsets.ISO_8859_1))
                bos.write("Thank you!\n\n".toByteArray(Charsets.ISO_8859_1))
                bos.write(byteArrayOf(LF, LF, LF))
                bos.write(byteArrayOf(GS, 0x56, 0x41, 0x10)) // Cut paper

                val bytes = bos.toByteArray()

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(printerIp.trim(), 9100), 4000)
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(true, "Voucher printed to $printerIp!")
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(false, "Print failed: ${e.message}")
                }
            }
        }.start()
    }
}
