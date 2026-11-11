package com.example.callrecorder

/** مدل و منطق خالص کتابخانه (بدون وابستگی به اندروید تا تست‌پذیر باشد). */
data class Rec(
    val path: String,
    val fileName: String,
    val startMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val number: String = "",
    val name: String = "",
    /** 1 = ورودی، 2 = خروجی، 0 = نامشخص */
    val direction: Int = 0,
    val enhanced: Boolean = false,
) {
    val key: String get() = Library.contactKey(number)
    val title: String get() = when {
        name.isNotBlank() -> name
        number.isNotBlank() -> number
        else -> "ناشناس"
    }
}

enum class SortMode { NEWEST, OLDEST, LONGEST, LARGEST, NAME }
enum class DirFilter { ALL, INCOMING, OUTGOING }

data class Group(val key: String, val title: String, val subtitle: String, val items: List<Rec>) {
    val totalMs: Long get() = items.sumOf { it.durationMs }
}

object Library {
    /** کلید یکتای مخاطب: آخر ۱۰ رقم شماره تا +98912…، 0912… و 912… یکی شوند. */
    fun contactKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.length < 4) return if (digits.isEmpty()) "" else digits
        return if (digits.length > 10) digits.takeLast(10) else digits.removePrefix("0")
    }

    fun filter(all: List<Rec>, query: String, dir: DirFilter, onlyEnhanced: Boolean = false): List<Rec> {
        val q = query.trim().lowercase()
        val qd = q.filter { it.isDigit() }
        return all.filter { r ->
            val okDir = when (dir) {
                DirFilter.ALL -> true
                DirFilter.INCOMING -> r.direction == 1
                DirFilter.OUTGOING -> r.direction == 2
            }
            val okQ = q.isEmpty() || r.name.lowercase().contains(q) ||
                r.number.contains(q) || (qd.length >= 3 && r.key.contains(qd.removePrefix("0"))) ||
                r.fileName.lowercase().contains(q)
            okDir && okQ && (!onlyEnhanced || r.enhanced)
        }
    }

    fun sort(list: List<Rec>, mode: SortMode): List<Rec> = when (mode) {
        SortMode.NEWEST -> list.sortedByDescending { it.startMs }
        SortMode.OLDEST -> list.sortedBy { it.startMs }
        SortMode.LONGEST -> list.sortedByDescending { it.durationMs }
        SortMode.LARGEST -> list.sortedByDescending { it.sizeBytes }
        SortMode.NAME -> list.sortedWith(compareBy({ it.title.lowercase() }, { -it.startMs }))
    }

    /** گروه‌بندی بر اساس مخاطب؛ مخاطب‌های بی‌شماره در گروه «ناشناس». */
    fun groupByContact(list: List<Rec>, mode: SortMode): List<Group> {
        val map = LinkedHashMap<String, MutableList<Rec>>()
        for (r in list) map.getOrPut(r.key) { mutableListOf() }.add(r)
        val groups = map.map { (k, v) ->
            val sorted = sort(v, if (mode == SortMode.NAME) SortMode.NEWEST else mode)
            val best = sorted.firstOrNull { it.name.isNotBlank() } ?: sorted.first()
            Group(k, best.title, best.number, sorted)
        }
        return when (mode) {
            SortMode.NAME -> groups.sortedBy { it.title.lowercase() }
            SortMode.OLDEST -> groups.sortedBy { g -> g.items.minOf { it.startMs } }
            SortMode.LONGEST -> groups.sortedByDescending { it.totalMs }
            SortMode.LARGEST -> groups.sortedByDescending { g -> g.items.sumOf { it.sizeBytes } }
            SortMode.NEWEST -> groups.sortedByDescending { g -> g.items.maxOf { it.startMs } }
        }
    }
}
