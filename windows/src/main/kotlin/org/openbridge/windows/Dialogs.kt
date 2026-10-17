package org.openbridge.windows

import java.io.File
import javax.swing.JFileChooser

/** Windows' own pickers (through Swing's system look and feel, set in main). */
object Dialogs {
    private var lastFolder: File? = null

    /** A folder for downloads, or null when cancelled. */
    fun chooseFolder(title: String): File? {
        val chooser = JFileChooser(lastFolder ?: File(System.getProperty("user.home"), "Downloads")).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showDialog(null, S.chooseFolderButton) != JFileChooser.APPROVE_OPTION) return null
        return chooser.selectedFile?.also { lastFolder = it }
    }

    /** Files and folders to upload, or none when cancelled. */
    fun chooseUploads(): List<File> {
        val chooser = JFileChooser(System.getProperty("user.home")).apply {
            dialogTitle = S.upload.trimEnd('…')
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return chooser.selectedFiles.toList()
    }
}
