package com.vayunmathur.notes.util

import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbConfigs: List<Pair<String, String>>
        get() {
            val pass = DatabaseHelper(this).getPassphrase()
            return listOf("notes-db" to pass)
        }

    override val extraFiles: List<File>
        get() = emptyList()
}
