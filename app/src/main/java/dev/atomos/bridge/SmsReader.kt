package dev.atomos.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class SmsMessageItem(val address: String, val body: String, val date: Long, val type: Int)

object SmsReader {
    const val WINDOW_DAYS = 7L
    const val MAX_MESSAGES = 100

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun readLastSevenDays(context: Context): List<SmsMessageItem> {
        check(hasPermission(context)) { "SMS permission is required on this device." }
        val since = System.currentTimeMillis() - WINDOW_DAYS * 24L * 60L * 60L * 1000L
        val rows = mutableListOf<SmsMessageItem>()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE)
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC LIMIT $MAX_MESSAGES"
        )?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (cursor.moveToNext()) {
                rows += SmsMessageItem(
                    cursor.getString(address).orEmpty().take(160),
                    cursor.getString(body).orEmpty().take(2000),
                    cursor.getLong(date),
                    cursor.getInt(type)
                )
            }
        }
        return rows
    }

    fun toJson(messages: List<SmsMessageItem>): JSONObject {
        val array = JSONArray()
        messages.take(MAX_MESSAGES).forEach { m ->
            array.put(JSONObject().put("address", m.address).put("body", m.body)
                .put("date", m.date).put("type", m.type))
        }
        return JSONObject().put("messages", array).put("count", array.length())
            .put("window_days", WINDOW_DAYS)
    }
}
