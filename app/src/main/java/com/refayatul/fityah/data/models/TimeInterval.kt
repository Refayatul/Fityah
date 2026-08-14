package com.refayatul.fityah.data.models

data class TimeInterval(
    var startHour: Int = 9,
    var startMinute: Int = 0,
    var endHour: Int = 17,
    var endMinute: Int = 0
)

fun MutableList<TimeInterval>.fixOvernightInterval(interval: TimeInterval): Boolean {
    val start = interval.startHour * 60 + interval.startMinute
    val end = interval.endHour * 60 + interval.endMinute

    if (start > end) {
        val index = indexOf(interval)
        if (index != -1) {
            removeAt(index)
            add(index, TimeInterval(interval.startHour, interval.startMinute, 24, 0))
            add(index + 1, TimeInterval(0, 0, interval.endHour, interval.endMinute))
            return true
        }
    }
    return false
}
