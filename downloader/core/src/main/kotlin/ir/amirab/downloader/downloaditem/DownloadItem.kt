package ir.amirab.downloader.downloaditem

import kotlinx.serialization.Serializable

@Serializable
data class DownloadItem(
    override var link: String,
    override var headers: Map<String, String>? = null,
    override var username: String? = null,
    override var password: String? = null,
    override var downloadPage: String? = null,
    override var userAgent: String? = null,

    var id: Long,
    var folder: String,
    var name: String,

    var contentLength: Long = LENGTH_UNKNOWN,
    var serverETag: String? = null,

    var dateAdded: Long = 0,
    var startTime: Long? = null,
    var completeTime: Long? = null,
    var status: DownloadStatus = DownloadStatus.Added,
    var preferredConnectionCount: Int? = null,
    var speedLimit: Long = 0,//0 is unlimited

    var fileChecksum: String? = null,
    override var m3u8: Boolean = false,
    override val m3u8Props: MutableMap<String, String> = mutableMapOf(),
) : IDownloadCredentials {
    companion object {
        const val LENGTH_UNKNOWN = -1L
    }
}

fun DownloadItem.applyFrom(other: DownloadItem) {
    link = other.link
    headers = other.headers
    username = other.username
    password = other.password
    downloadPage = other.downloadPage
    userAgent = other.userAgent

    id = other.id
    folder = other.folder
    name = other.name

    contentLength = other.contentLength
    serverETag = other.serverETag

    dateAdded = other.dateAdded
    startTime = other.startTime
    completeTime = other.completeTime
    status = other.status
    preferredConnectionCount = other.preferredConnectionCount
    speedLimit = other.speedLimit

    fileChecksum = other.fileChecksum
    m3u8 = other.m3u8
}

fun DownloadItem.withCredentials(credentials: IDownloadCredentials) = apply {
    link = credentials.link
    headers = credentials.headers
    username = credentials.username
    password = credentials.password
    downloadPage = credentials.downloadPage
    userAgent = credentials.userAgent
    m3u8 = credentials.m3u8
    m3u8Props.putAll(credentials.m3u8Props)
}

enum class DownloadStatus {
    Error,
    Added,
    Paused,
    Downloading,
    Completed,
}
