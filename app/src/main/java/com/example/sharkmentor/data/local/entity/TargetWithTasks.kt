package com.example.sharkmentor.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class TargetWithTasks(
    @Embedded val target: TargetEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "targetId"
    )
    val tasks: List<TaskEntity>
)
