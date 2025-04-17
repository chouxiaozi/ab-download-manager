package ir.amirab.downloader.destination

import ir.amirab.downloader.connection.DownloaderClient
import ir.amirab.downloader.downloaditem.DownloadItem
import ir.amirab.downloader.part.Part
import ir.amirab.downloader.utils.EmptyFileCreator
import ir.amirab.downloader.utils.IDiskStat
import ir.amirab.downloader.utils.checkSegKey
import ir.amirab.downloader.utils.concatToMp4Pro
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

class M3u8DownloadDestination(
    val file: File,
    private val diskStat: IDiskStat,
    private val emptyFileCreator: EmptyFileCreator,
    val downloadItem: DownloadItem,
    val client: DownloaderClient,
) : DownloadDestination(File(file.path + "-parts")) {
    private val fileHandles = mutableListOf<FileHandle>()


    override fun getWriterFor(part: Part): DestWriter {
        val returned = returnIfAlreadyHaveWriter(part.from)
        returned?.let { return it }

        val outFile = File(outputFile, part.from.toString() + ".ts")
        if (outFile.exists()) {
            outFile.delete()
        }
        outFile.createNewFile()
        val fileHandle = FileSystem.SYSTEM.openReadWrite(outFile.toOkioPath())
        val writer = DestWriter(
            part.from,
            outFile,
            part.from,
            part.current,
            fileHandle,
        )
        synchronized(this) {
            fileParts.add(writer)
            fileHandles.add(fileHandle)
        }
        return writer
    }

    override fun canGetFileWriter(): Boolean {
        return true
    }

    override suspend fun prepareFile(onProgressUpdate: (Int?) -> Unit) {
        outputFile.let {
            it.canonicalFile.mkdirs()
            if (!it.exists()) {
                error("can't create folder for destination file $it")
            }

            if (!it.isDirectory) {
                error("$outputFile is not a directory")
            }
        }
    }

    override suspend fun isDownloadedPartsIsValid(): Boolean {
        return true
    }

    override fun onAllPartsCompleted() {
        super.onAllPartsCompleted()
        checkSegKey()
        concatToMp4Pro(file, outputFile)
    }

    override fun flush() {
        //转换mp4
        runCatching {
            for (fileHandle in fileHandles) {
                fileHandle.flush()
            }
        }
    }

    override fun onAllFilePartsRemoved() {
        super.onAllFilePartsRemoved()
        synchronized(this) {
            for (fileHandle in fileHandles) {
                fileHandle.close()
            }
            fileHandles.clear()
        }
    }

}