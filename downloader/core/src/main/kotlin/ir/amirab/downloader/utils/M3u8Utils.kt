package ir.amirab.downloader.utils

import arrow.atomic.AtomicInt
import arrow.atomic.AtomicLong
import io.lindstrom.m3u8.model.KeyMethod
import io.lindstrom.m3u8.model.MediaPlaylist
import io.lindstrom.m3u8.parser.MediaPlaylistParser
import ir.amirab.downloader.connection.DownloaderClient
import ir.amirab.downloader.connection.response.ResponseInfo
import ir.amirab.downloader.destination.M3u8DownloadDestination
import ir.amirab.downloader.downloaditem.DownloadCredentials
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.part.Part
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeHex
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.ffmpeg
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path


const val segKeyUri = "X-KEY-URI"
const val segMethod = "X-KEY-METHOD"
const val segIV = "EXT-X-KEY"
val mediaParser = MediaPlaylistParser()
val m3u8Parts = ConcurrentHashMap<String, M3u8Info>()

suspend fun parseM3u8(
    client: DownloaderClient, credentials: IDownloadCredentials
): ResponseInfo {
    val connect = client.connect(credentials, null, null);
    val readPlaylist = mediaParser.readPlaylist(connect.source.inputStream())
    readPlaylist.mediaSegments()?.get(0)?.segmentKey()?.ifPresent { key ->
        key.uri().ifPresent {
            credentials.m3u8Props[segKeyUri] = URI(credentials.link).resolve(it).toString()
            credentials.m3u8Props[segMethod] = key.method().toString()
            credentials.m3u8Props[segIV] = key.iv().get()
        }
    }
    val m3u8Info = m3u8Parts.computeIfAbsent(credentials.link) {
        runBlocking {
            loadSegInfo(readPlaylist, client, credentials)
        }
    }
    val resp = connect.responseInfo
    if (resp.responseHeaders is MutableMap) {
        resp.responseHeaders["content-length"] = m3u8Info.length.toString()
    }
    return resp
}


fun loadSegInfo(
    readPlaylist: MediaPlaylist, client: DownloaderClient, credentials: IDownloadCredentials
): M3u8Info {
    val index = AtomicInt(0)
    val totalLength = AtomicLong(0)
    val parts = readPlaylist.mediaSegments().parallelStream().map {
        runBlocking {
            val realUri = URI(credentials.link).resolve(it.uri()).toString()
            val testInfo = client.test(
                DownloadCredentials(
                    link = realUri,
                    headers = credentials.headers,
                    username = credentials.username,
                    password = credentials.password,
                    downloadPage = credentials.downloadPage,
                    userAgent = credentials.userAgent
                )
            )
            val testTotal = testInfo.totalLength
            testTotal?.let { it1 -> totalLength.addAndGet(it1) }
            Part(
                index.incrementAndGet().toLong(), testTotal?.minus(1), 0, realUri
            )
        }

    }.toList()
    return M3u8Info(totalLength.get(), parts)
}

fun resolvePartInfo(credentials: IDownloadCredentials): List<Part> {
    return m3u8Parts.remove(credentials.link)?.parts!!
}

fun M3u8DownloadDestination.checkSegKey() {
    val keyUri = downloadItem.m3u8Props[segKeyUri]
    if (!keyUri.isNullOrEmpty()) {
        val method = this.downloadItem.m3u8Props[segMethod]
        when (KeyMethod.parse(method)) {
            KeyMethod.NONE -> return
            KeyMethod.AES_128 -> {
                val keyHex = runBlocking {
                    client.connect(
                        downloadItem.copy(link = keyUri), null, null
                    ).source.readByteArray()
                }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").also {
                    val iv = IvParameterSpec(
                        downloadItem.m3u8Props[segIV]!!.substring(2).decodeHex().toByteArray()
                    )
                    val key = SecretKeySpec(keyHex, "AES")
                    it.init(Cipher.DECRYPT_MODE, key, iv)
                }
                for (item in this.outputFile.listFiles()) {
                    val inputBytes = item.inputStream().readAllBytes()
                    val outByte = cipher.doFinal(inputBytes)
                    Files.write(item.toPath(), outByte)
                }
            }

            KeyMethod.SAMPLE_AES -> TODO()
            KeyMethod.SAMPLE_AES_CTR -> TODO()
        }
    }
}

fun concatToMp4(file: File, outputFile: File) {
    avutil.av_log_set_level(avutil.AV_LOG_DEBUG)
    // 初始化 FFmpeg 库
    // avformat.av_register_all() // 注册所有格式和编解码器
    avformat.avformat_network_init() // 初始化网络支持
    val outputPath = file.toString()

    // 创建输出上下文
    var outputFormatContext: AVFormatContext? = null
    try {
        val formatName = BytePointer("mp4") // 指定输出格式为 MP4
        outputFormatContext = AVFormatContext()
        var ret = avformat_alloc_output_context2(
            outputFormatContext,
            null,
            formatName,
            BytePointer(outputPath)
        )
        if (ret < 0) {
            throw RuntimeException(
                "Could not create output context: " + ret.toErrorStr()
            )
        }
        val buffer = ByteArray(1024 * 1024) // 1MB缓冲区

        // 打开输出文件
        val pb = avformat.avio_alloc_context(buffer, buffer.size, 1, null, null, null, null)
        if ((avformat.avio_open(pb, BytePointer(outputPath), avformat.AVIO_FLAG_WRITE)
                .also { ret = it }) < 0
        ) {
            throw RuntimeException(
                "Could not open output file: " + ret.toErrorStr()
            )
        }
        outputFormatContext.pb(pb)
        // avformat.avformat_write_header(outputFormatContext, AVDictionary null )
        // 遍历输入文件并复制流
        for (i in outputFile.list().map { it.substring(0, it.length - 3).toInt() }.sorted()) {
            val inputFormatContext = AVFormatContext(null)
            val tsFile = Path(outputFile.toString(), "$i.ts").toString()
            try {
                if ((avformat.avformat_open_input(
                        inputFormatContext,
                        BytePointer(tsFile),
                        null,
                        null
                    ).also { ret = it }) < 0
                ) {
                    throw RuntimeException("Could not open input file: " + ret.toErrorStr())
                }

                // 获取输入文件的流信息
                if ((avformat.avformat_find_stream_info(
                        inputFormatContext, null as PointerPointer<*>?
                    ).also { ret = it }) < 0
                ) {
                    throw RuntimeException(
                        "Could not find stream information: " + ret.toErrorStr()
                    )
                }

                // 复制流到输出上下文
                for (i in 0..<inputFormatContext.nb_streams()) {
                    val inputStream = inputFormatContext.streams(i)
                    val outputStream = avformat.avformat_new_stream(outputFormatContext, null)
                    if (outputStream == null) {
                        throw RuntimeException("Failed to allocate output stream." + ret.toErrorStr())
                    }
                    if ((avcodec.avcodec_parameters_copy(
                            outputStream.codecpar(), inputStream.codecpar()
                        ).also { ret = it }) < 0
                    ) {
                        throw RuntimeException(
                            "Failed to copy codec parameters: " + ret.toErrorStr()
                        )
                    }
                    outputStream.codecpar().codec_tag(0) // 清除 codec_tag
                }

                // 写入数据包
                val packet = AVPacket()
                try {
                    while (avformat.av_read_frame(inputFormatContext, packet) >= 0) {
                        avformat.av_interleaved_write_frame(outputFormatContext, packet)
                        avcodec.av_packet_unref(packet)
                    }
                } finally {
                    packet.close()
                }
            } finally {
                avformat.avformat_close_input(inputFormatContext)
            }
        }

        // 写入尾部信息
        avformat.av_write_trailer(outputFormatContext)
    } finally {
        // 清理资源
        if (outputFormatContext != null) {
            //if (!(outputFormatContext.oformat().flags() and avformat.AVFMT_NOFILE)) {
            //   avformat.avio_closep(outputFormatContext.pb())
            // }
            avformat.avformat_free_context(outputFormatContext)
        }
    }

}

fun concatToMp4Pro(file: File, outputFile: File) {
    val input = outputFile.list().map { it.substring(0, it.length - 3).toInt() }.sorted()
        .joinToString("|", "concat:") { Path(outputFile.toString(), "$it.ts").toString() }
    val ffmpeg = Loader.load(ffmpeg::class.java)
    val pb = ProcessBuilder(
        ffmpeg,
        "-i",
        input,
        file.toString()
    )
    pb.inheritIO().start().waitFor()
    outputFile.deleteRecursively()
}



private fun Int.toErrorStr(): String? {
    // 创建缓冲区用于存储错误信息
    val errorBuffer = BytePointer(avutil.AV_ERROR_MAX_STRING_SIZE.toLong())

    // 调用 av_make_error_string 将错误代码转换为字符串
    avutil.av_make_error_string(errorBuffer, avutil.AV_ERROR_MAX_STRING_SIZE.toLong(), this)

    // 将结果转换为 Java 字符串并返回
    return errorBuffer.string
}

data class M3u8Info(
    val length: Long, val parts: List<Part>
)