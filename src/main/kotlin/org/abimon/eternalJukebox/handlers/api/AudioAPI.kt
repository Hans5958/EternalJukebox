package org.abimon.eternalJukebox.handlers.api

import com.github.kittinunf.fuel.Fuel
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.abimon.eternalJukebox.*
import org.abimon.eternalJukebox.data.audio.YoutubeAudioSource
import org.abimon.eternalJukebox.objects.EnumStorageType
import org.abimon.visi.io.FileDataSource
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object AudioAPI : IAPI {
    override val mountPath: String = "/audio"
    override val name: String = "Audio"
    val format: String
        get() = EternalJukebox.config.audioSourceOptions["AUDIO_FORMAT"] as? String ?: "m4a"
    val uuid: String
        get() = UUID.randomUUID().toString()

    val base64Encoder: Base64.Encoder by lazy { Base64.getUrlEncoder() }

    val mime: String
        get() = EternalJukebox.config.audioSourceOptions["AUDIO_MIME"] as? String ?: run {
            when (format) {
                "m4a" -> return@run "audio/mp4"
                "aac" -> return@run "audio/aac"
                "mp3" -> return@run "audio/mpeg"
                "ogg" -> return@run "audio/ogg"
                "wav" -> return@run "audio/wav"
                else -> return@run "audio/mpeg"
            }
        }

    override fun setup(router: Router) {
        router.get("/jukebox/:id").blockingHandler(AudioAPI::jukeboxAudio)
        router.get("/external").blockingHandler(AudioAPI::externalAudio)
    }

    fun jukeboxAudio(context: RoutingContext) {
        if (EternalJukebox.storage.shouldStore(EnumStorageType.AUDIO)) {
            val id = context.pathParam("id")
            val update = context.request().getParam("update")?.toBoolean() ?: false
            if (EternalJukebox.storage.isStored("$id.$format", EnumStorageType.AUDIO) && !update) {
                if (EternalJukebox.storage.provide("$id.$format", EnumStorageType.AUDIO, context, context.clientInfo))
                    return

                val data = EternalJukebox.storage.provide("$id.$format", EnumStorageType.AUDIO, context.clientInfo)
                if (data != null)
                    return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(data, mime)
            }

            if (update)
                log("[${context.clientInfo.userUID}] ${context.request().connection().remoteAddress()} is requesting an update for $id")

            val track = EternalJukebox.spotify.getInfo(id, context.clientInfo) ?: run {
                log("[${context.clientInfo.userUID}] No track info for $id; returning 400")
                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(jsonObjectOf(
                        "error" to "Track info not found for $id",
                        "client_uid" to context.clientInfo.userUID
                ))
            }

            val audio = EternalJukebox.audio.provide(track, context.clientInfo)

            if (audio == null)
                context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(400).end(jsonObjectOf(
                        "error" to "Audio is null",
                        "client_uid" to context.clientInfo.userUID
                ))
            else {
                if (EternalJukebox.storage.isStored("$id.$format", EnumStorageType.AUDIO) && !update) {
                    if (EternalJukebox.storage.provide("$id.$format", EnumStorageType.AUDIO, context, context.clientInfo))
                        return

                    val data = EternalJukebox.storage.provide("$id.$format", EnumStorageType.AUDIO, context.clientInfo)
                    if (data != null)
                        return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(data, mime)
                }

                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(audio, mime)
            }
        } else {
            context.response().putHeader("X-Client-UID", context.clientInfo.userUID).setStatusCode(501).end(jsonObjectOf(
                    "error" to "Configured storage method does not support storing AUDIO",
                    "client_uid" to context.clientInfo.userUID
            ))
        }
    }

    // url -> fallbackURL -> fallbackID
    fun externalAudio(context: RoutingContext) {
        val url = context.request().getParam("url")

        if (url != null) {
            val (_, response, _) = Fuel.head(url).response()
            if (response.statusCode < 300) {
                val mime = response.headers["Content-Type"]?.firstOrNull()

                if (mime != null && mime.startsWith("audio"))
                    return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).redirect(url)
                else {
                    if (EternalJukebox.storage.shouldStore(EnumStorageType.EXTERNAL_AUDIO)) {
                        val b64 = base64Encoder.encodeToString(url.toByteArray(Charsets.UTF_8))

                        val update = context.request().getParam("update")?.toBoolean() ?: false
                        if (EternalJukebox.storage.isStored("$b64.$format", EnumStorageType.EXTERNAL_AUDIO) && !update) {
                            if (EternalJukebox.storage.provide("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context, context.clientInfo))
                                return

                            val data = EternalJukebox.storage.provide("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context.clientInfo)
                            if (data != null)
                                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(data, AudioAPI.mime)
                        }

                        if (update)
                            log("[${context.clientInfo.userUID}] ${context.request().connection().remoteAddress()} is requesting an update for $url / $b64")

                        val tmpFile = File("$uuid.tmp")
                        val tmpLog = File("$b64-$uuid.log")
                        val ffmpegLog = File("$b64-$uuid.log")
                        val endGoalTmp = File(tmpFile.absolutePath.replace(".tmp", ".$format"))

                        try {
                            val downloadProcess = ProcessBuilder().command(ArrayList(YoutubeAudioSource.command).apply {
                                add(url)
                                add(tmpFile.absolutePath)
                                add(YoutubeAudioSource.format)
                            }).redirectErrorStream(true).redirectOutput(tmpLog).start()

                            downloadProcess.waitFor(60, TimeUnit.SECONDS)

                            if (!endGoalTmp.exists()) {
                                log("[${context.clientInfo.userUID}] $endGoalTmp does not exist, attempting to convert with ffmpeg")

                                if (!tmpFile.exists())
                                    return log("[${context.clientInfo.userUID}] $tmpFile does not exist, what happened?", true)

                                if (MediaWrapper.ffmpeg.installed) {
                                    if (!MediaWrapper.ffmpeg.convert(tmpFile, endGoalTmp, ffmpegLog))
                                        return log("[${context.clientInfo.userUID}] Failed to convert $tmpFile to $endGoalTmp", true)

                                    if (!endGoalTmp.exists())
                                        return log("[${context.clientInfo.userUID}] $endGoalTmp still does not exist, what happened?", true)
                                } else
                                    return log("[${context.clientInfo.userUID}] ffmpeg not installed, nothing we can do", true)
                            }

                            endGoalTmp.useThenDelete { EternalJukebox.storage.store("$b64.${YoutubeAudioSource.format}", EnumStorageType.EXTERNAL_AUDIO, FileDataSource(it), context.clientInfo) }

                            if (EternalJukebox.storage.provide("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context, context.clientInfo))
                                return

                            val data = EternalJukebox.storage.provide("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, context.clientInfo)
                            if (data != null)
                                return context.response().putHeader("X-Client-UID", context.clientInfo.userUID).end(data, AudioAPI.mime)
                        } finally {
                            tmpFile.delete()
                            tmpLog.useThenDelete { EternalJukebox.storage.store(it.name, EnumStorageType.LOG, FileDataSource(it), context.clientInfo) }
                            ffmpegLog.useThenDelete { EternalJukebox.storage.store(it.name, EnumStorageType.LOG, FileDataSource(it), context.clientInfo) }
                            endGoalTmp.useThenDelete { EternalJukebox.storage.store("$b64.$format", EnumStorageType.EXTERNAL_AUDIO, FileDataSource(it), context.clientInfo) }
                        }
                    }
                }
            }
        }

        context.reroute("/api" + mountPath + "/jukebox/${context.request().getParam("fallbackID") ?: "7GhIk7Il098yCjg4BQjzvb"}")
    }

    init {
        log("Initialised Audio API")
    }
}