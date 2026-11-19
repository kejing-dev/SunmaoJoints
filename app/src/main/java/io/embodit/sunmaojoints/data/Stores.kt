package io.embodit.sunmaojoints.data

import android.content.Context
import android.util.Log
import io.embodit.sunmaojoints.domain.Progress
import io.embodit.sunmaojoints.domain.ProgressStore
import io.embodit.sunmaojoints.domain.SaveCodec
import java.io.File

/**
 * files/progress.json (design dataModel.storage): a temp file, a read-back check, then a rename. A file that is there
 * but cannot be read starts a new user and shows S14 once.
 */
class FileProgressStore(context: Context) : ProgressStore {
    private val dir = context.filesDir
    private val file = File(dir, "progress.json")

    override fun load(): Pair<Progress, Boolean> {
        if (!file.exists()) return Progress() to false
        return runCatching { SaveCodec.decode(file.readText()) to false }
            .getOrElse { Log.w("SunmaoJoints", "SAVE unreadable, new user (S14): $it"); Progress() to true }
    }

    override fun save(p: Progress) {
        val text = SaveCodec.encode(p)
        runCatching {
            val tmp = File(dir, "progress.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == p) { "read-back mismatch" }
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("SunmaoJoints", "SAVE failed", it) }
    }

    /** Debug: write garbage so the next start shows S14. */
    fun corrupt() = runCatching { file.writeText("{not json") }
}
