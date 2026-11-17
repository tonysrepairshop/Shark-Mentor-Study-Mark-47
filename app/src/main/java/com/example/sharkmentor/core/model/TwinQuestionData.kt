package com.example.sharkmentor.core.model

import org.json.JSONArray
import org.json.JSONObject

data class TwinQuestionData(
    val questionText: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("questionText", questionText)
        val arr = JSONArray()
        options.forEach { arr.put(it) }
        obj.put("options", arr)
        obj.put("correctIndex", correctIndex)
        obj.put("explanation", explanation)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): TwinQuestionData {
            val obj = JSONObject(jsonStr)
            val arr = obj.getJSONArray("options")
            val opts = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                opts.add(arr.getString(i))
            }
            return TwinQuestionData(
                questionText = obj.getString("questionText"),
                options = opts,
                correctIndex = obj.getInt("correctIndex"),
                explanation = obj.getString("explanation")
            )
        }
    }
}
