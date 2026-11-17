package com.example.sharkmentor.core.ai

import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType

data class ParsedTarget(
    val title: String,
    val type: TargetType,
    val period: TargetPeriod,
    val tasks: List<String>,
    val estimatedDeadlineMillis: Long
)

data class ParsedScheduleResult(
    val targets: List<ParsedTarget>
)
