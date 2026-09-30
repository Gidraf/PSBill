package com.example.psbill.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.print.PrintHelper

/**
 * AndroidPrintHelper — Standard Android Printing for InkJet/Office printers (like Epson L3250).
 * Uses the Android Print Spooler which works with the Epson Print Service plugin.
 */
object AndroidPrintHelper {

    fun printTestPage(context: Context) {
        val printHelper = PrintHelper(context)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT
        
        // Create a bitmap representing the receipt
        val bitmap = createReceiptBitmap("TEST PRINT", listOf(
            "AJIRIWA CLIENT",
            "----------------",
            "Printer: Epson L3250 Series",
            "Status: Connected via Android",
            "----------------",
            "Ready for vouchers and receipts."
        ))
        
        printHelper.printBitmap("Ajiriwa_Test_Print", bitmap)
    }

    fun printOrderReceipt(
        context: Context,
        orderId: String,
        customer: String,
        amount: String,
        status: String,
        paymentMethod: String = "CASH",
        mpesaCode: String = ""
    ) {
        val printHelper = PrintHelper(context)
        printHelper.scaleMode = PrintHelper.SCALE_MODE_FIT

        val lines = mutableListOf<String>().apply {
            add("ORDER #${orderId.takeLast(8).uppercase()}")
            add("--------------------------------")
            add("Customer: $customer")
            add("Total: KSh $amount")
            add("Status: $status")
            add("Payment: $paymentMethod")
            if (mpesaCode.isNotBlank()) {
                add("M-Pesa Ref: $mpesaCode")
            }
            add("--------------------------------")
            add("Thank you for your business!")
            add("Powered by Ajiriwa")
        }

        val bitmap = createReceiptBitmap("ORDER RECEIPT", lines)
        printHelper.printBitmap("Order_${orderId.takeLast(6)}", bitmap)
    }


    private fun createReceiptBitmap(title: String, lines: List<String>): Bitmap {
        val width = 400
        val lineHeight = 40
        val height = (lines.size + 4) * lineHeight
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        var y = 60f
        canvas.drawText(title, 20f, y, titlePaint)
        y += 50f
        
        lines.forEach { line ->
            canvas.drawText(line, 20f, y, paint)
            y += lineHeight
        }
        
        return bitmap
    }
}
