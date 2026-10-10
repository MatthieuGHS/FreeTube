package fr.appartfinder.freetube.download

import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.workDataOf

/** Every failure the user can see: the French label, and whether retrying (with backoff) can help. */
enum class PipelineError(val label: String, val transient: Boolean) {
    VIDEO_UNAVAILABLE("Vidéo indisponible (supprimée ou privée)", false),
    GEO_BLOCKED("Vidéo bloquée dans ce pays", false),
    LOGIN_REQUIRED("Vidéo réservée aux comptes connectés", false),
    UNSUPPORTED("Format non pris en charge (direct ou durée > 24 h)", false),
    ENGINE_OUTDATED("Moteur obsolète : mettez-le à jour dans Réglages", true),
    NETWORK("Réseau indisponible", true),
    NO_SPACE("Espace de stockage insuffisant", false),
    NOT_CONFIGURED("Token d'appareil manquant : renseignez-le dans Réglages", false),
    UNAUTHORIZED("Token refusé par le serveur : vérifiez-le dans Réglages", false),
    SERVER("Le serveur n'a pas pu enregistrer le morceau", true),
    STORAGE("Envoi du fichier vers le stockage échoué", true),
    UNKNOWN("Erreur inattendue", false),
}

/** Message = enum name: never a URL, a token or a stderr dump. */
class PipelineException(val error: PipelineError, cause: Throwable? = null) : Exception(error.name, cause)

private const val KEY_ERROR = "error"
const val MAX_ATTEMPTS = 5 // backoff 30 s, 1, 2, 4 min, then the error is shown

/** The one retry rule, shared by both workers. */
fun CoroutineWorker.outcome(error: PipelineError): Result =
    if (error.transient && runAttemptCount < MAX_ATTEMPTS - 1) {
        Result.retry()
    } else {
        Result.failure(workDataOf(KEY_ERROR to error.name))
    }

fun Data.pipelineError(): PipelineError? = getString(KEY_ERROR)?.let { name -> PipelineError.entries.firstOrNull { it.name == name } }

/** Pure. yt-dlp stderr -> error. Most specific phrase first ("Private video … Sign in" is UNAVAILABLE). */
fun classifyYtDlpError(stderr: String): PipelineError {
    val s = stderr.lowercase()
    return when {
        "no space left" in s -> PipelineError.NO_SPACE
        "available in your country" in s || "geo restrict" in s || "geo-restrict" in s -> PipelineError.GEO_BLOCKED
        "private video" in s || "video unavailable" in s || "has been removed" in s -> PipelineError.VIDEO_UNAVAILABLE
        "sign in to confirm" in s || "members-only" in s || "join this channel" in s -> PipelineError.LOGIN_REQUIRED
        "is live" in s || "live event" in s || "premieres in" in s -> PipelineError.UNSUPPORTED
        "http error 403" in s -> PipelineError.ENGINE_OUTDATED
        "unable to download" in s || "timed out" in s || "getaddrinfo" in s || "errno 7" in s ||
            "connection" in s || "http error 5" in s -> PipelineError.NETWORK
        else -> PipelineError.UNKNOWN
    }
}

/** Pure. Server status -> error. 409 = a PUT was lost; 404 on complete = deleted on the web meanwhile:
 * retrying re-runs upload, which resurrects it (phone wins, ADR 0003). 422 = DTO bug, retrying won't help. */
internal fun httpError(code: Int): PipelineError = when (code) {
    401, 403 -> PipelineError.UNAUTHORIZED
    404, 409, 429, in 500..599 -> PipelineError.SERVER
    else -> PipelineError.UNKNOWN
}
