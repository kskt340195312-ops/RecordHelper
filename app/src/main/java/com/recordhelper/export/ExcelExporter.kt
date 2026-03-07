package com.recordhelper.export

import android.content.Context
import android.os.Environment
import com.recordhelper.data.VideoRecordEntity
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 导出记录到 Excel 文件
 */
object ExcelExporter {

    fun export(context: Context, records: List<VideoRecordEntity>): File? {
        return try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("视频记录")

            // 表头
            val headerRow = sheet.createRow(0)
            val headers = listOf("序号", "商家名", "互动数", "发布时间", "记录时间", "状态")
            headers.forEachIndexed { index, title ->
                headerRow.createCell(index).setCellValue(title)
            }

            // 数据行
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            records.forEachIndexed { index, record ->
                val row = sheet.createRow(index + 1)
                row.createCell(0).setCellValue((index + 1).toDouble())
                row.createCell(1).setCellValue(record.merchantName)
                row.createCell(2).setCellValue(record.interactionCount)
                row.createCell(3).setCellValue(record.analyzedPublishTime)
                row.createCell(4).setCellValue(dateFormat.format(Date(record.createdAt)))
                row.createCell(5).setCellValue(record.status.name)
            }

            // 保存文件
            val fileName = "记录助手_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())}.xlsx"
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, fileName)
            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
