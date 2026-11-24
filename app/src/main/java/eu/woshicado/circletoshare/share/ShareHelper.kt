package eu.woshicado.circletoshare.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Toast
import androidx.core.content.FileProvider
import eu.woshicado.circletoshare.Prefs
import eu.woshicado.circletoshare.R
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** What to do with a crop. */
enum class Delivery { SHARE, COPY, SAVE }

/** Where a crop went: [uri] to open it by, and for a save the [folder] it
 *  was put in, to name to the user. */
class Delivered(val uri: Uri, val folder: String? = null)

/**
 * Shared and copied crops live only in the app's cache dir — never in the
 * gallery. Old files are swept on every session so nothing needs manual
 * cleanup. Only [Delivery.SAVE] puts a crop in the gallery, where it stays.
 */
object ShareHelper {

    private const val AUTHORITY = "eu.woshicado.circletoshare.fileprovider"
    private const val DIR = "shots"
    private const val MAX_AGE_MS = 60L * 60L * 1000L // 1 hour

    // Pictures/Screenshots: where Save puts images unless told otherwise,
    // and whenever the folder it was told can't be used.
    // (Environment.DIRECTORY_SCREENSHOTS only exists from Android 12.)
    private val DEFAULT_DIR = "${Environment.DIRECTORY_PICTURES}/Screenshots"
    // The device's own storage (and memory cards), as Android's folder
    // picker shows it.
    internal const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    private const val FOLDER_NAME_MAX = 64

    // Where crops are written: off the thread that draws the crop tool,
    // which a long scrollshot's PNG or a slow folder would otherwise hold
    // up. One at a time, so two saves land in the order they were asked for.
    // Saves have a thread of their own: a folder that never answers (a
    // cloud's, offline; a card being taken out) then holds up only saves,
    // not a Share or a Copy, which the user is waiting on with the tool up.
    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor { Thread(it, "save") }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "deliver") }

    /**
     * The part of [source] a delivery of [rect] (null = whole bitmap) is
     * of, or null if there is nothing valid to deliver: the caller's tool
     * then stays as it is. Cheap; asked on the main thread before [deliver].
     */
    fun bounds(source: Bitmap, rect: Rect?): Rect? {
        // A copy: the caller's rect is not ours to clamp. intersect() leaves
        // the rect untouched when there is no overlap at all, so its result
        // is the "nothing valid to deliver" answer, not something to ignore.
        val bounds = Rect(rect ?: Rect(0, 0, source.width, source.height))
        if (!bounds.intersect(0, 0, source.width, source.height)) return null
        if (bounds.width() < 4 || bounds.height() < 4) return null
        return bounds
    }

    /**
     * Crops [source] to [bounds] (see [bounds]) and writes it, in the
     * background: to the cache for Share and Copy, to the gallery for Save.
     * [app]: the package of the app the picture is of, if known, for a save
     * into one folder per app (see [Prefs.isSavePerApp]). [done] gets, on the
     * main thread, where the image now is — to [handOver] for Share and
     * Copy — or the failure: an [IOException] when the image could not be
     * written, anything else unexpected; the caller reports either. Saying
     * that a save worked is the caller's job too: a toast from here goes
     * unseen (see ScreenshotAccessibilityService.notify).
     */
    fun deliver(
        context: Context, source: Bitmap, bounds: Rect, action: Delivery, app: String?,
        done: (Result<Delivered>) -> Unit,
    ) {
        val appContext = context.applicationContext
        (if (action == Delivery.SAVE) saver else worker).execute {
            val result = runCatching { write(appContext, source, bounds, action, app) }
            main.post { done(result) }
        }
    }

    /** What [deliver] does in the background, here and now: blocking, on
     *  any thread. */
    internal fun write(context: Context, source: Bitmap, bounds: Rect, action: Delivery, app: String?): Delivered {
        val cropped = Bitmap.createBitmap(
            source, bounds.left, bounds.top, bounds.width(), bounds.height()
        )
        if (action == Delivery.SAVE) return save(context, cropped, app)
        return Delivered(writeAndGetUri(context, cropped))
    }

    /** What to tell the user of a [deliver] that failed with [error]. */
    fun failureMessage(error: Throwable): Int =
        if (error is IOException) R.string.error_save_failed else R.string.error_capture_failed

    /** What to tell the user of how a save went: where it went, or why not. */
    fun resultMessage(context: Context, result: Result<Delivered>): String = result.fold(
        { context.getString(R.string.saved, it.folder) },
        { context.getString(failureMessage(it)) }
    )

    /**
     * The rest of a Share or a Copy, once [deliver] has written the image:
     * the share sheet, or the image on the clipboard. On the main thread,
     * from [context] while its window is still up — the share sheet is an
     * activity start, which Android allows a window in view. Nothing to do
     * for a Save.
     */
    fun handOver(context: Context, action: Delivery, delivered: Delivered) {
        val uri = delivered.uri
        if (action == Delivery.SHARE) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, context.getString(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        } else if (action == Delivery.COPY) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "screenshot", uri))
            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open the saved image [uri] in the user's app for pictures — their
     * default, or the system's pick-an-app sheet if they have none. Returns
     * false if no app can show it.
     */
    fun view(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "image/png")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // As openQr: whatever starting it provokes stays here.
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** [message] followed by a "View" action, as a snackbar shows one: for
     *  a notice that opens the saved image when tapped. */
    fun withViewAction(context: Context, message: CharSequence): CharSequence =
        SpannableStringBuilder(message).append("   ").apply {
            val start = length
            append(context.getString(R.string.action_view))
            // A light tint of the launcher icon's purple, for the dark pill.
            setSpan(ForegroundColorSpan(Color.rgb(207, 196, 255)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun writeAndGetUri(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "crop-${System.currentTimeMillis()}.png")
        try {
            file.outputStream().use { out -> writePng(bitmap, out) }
        } catch (e: IOException) {
            // Don't leave half an image behind for something to pick up.
            file.delete()
            throw e
        }
        return FileProvider.getUriForFile(context, AUTHORITY, file)
    }

    /**
     * Save [bitmap]: into the folder the user picked, if they did and it can
     * still be written to, else — as before there was a choice — into
     * Pictures/Screenshots. Either one with a folder of [app]'s own in it,
     * if the user wants that and the app is known.
     */
    private fun save(context: Context, bitmap: Bitmap, app: String?): Delivered {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "Screenshot_$stamp.png"
        val sub = app?.takeIf { Prefs.isSavePerApp(context) }?.let { appFolder(context, it) }
        Prefs.getSaveDir(context)?.let { dir ->
            // Anything at all wrong with the folder, gone, its grant revoked,
            // the card it was on taken out, is a save to the default one, as
            // the notice then says: the image is not lost for it.
            runCatching { saveToTree(context, Uri.parse(dir), bitmap, name, sub) }
                .onSuccess { return it }
                .onFailure { android.util.Log.w("CircleToShare", "save folder unusable, saving to $DEFAULT_DIR", it) }
        }
        val uri = saveToGallery(context, bitmap, name, sub)
        return Delivered(uri, listOfNotNull(DEFAULT_DIR.substringAfterLast('/'), sub).joinToString("/"))
    }

    /**
     * Add [bitmap] to the gallery, in Pictures/Screenshots beside the
     * system's own screenshots ([sub]: in that folder of it). Needs no
     * permission: since Android 10 an app may add media to the shared
     * collections without one.
     *
     * Every failure is an [IOException], whatever the media store threw:
     * storage that's full, or shared storage that isn't there at all (the
     * media store's IllegalStateException and kin), is "couldn't save" to
     * the user, not a failed capture.
     */
    private fun saveToGallery(context: Context, bitmap: Bitmap, name: String, sub: String?): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            // (A name already taken gets a " (1)" from the media store.)
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, listOfNotNull(DEFAULT_DIR, sub).joinToString("/"))
            // Hidden from other apps until it is written in full.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            throw e as? IOException ?: IOException("the gallery refused the image", e)
        } ?: throw IOException("the gallery refused the image")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("the gallery entry could not be opened")
            out.use { writePng(bitmap, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            // As in writeAndGetUri: no half an image left behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e as? IOException ?: IOException("the image could not be saved", e)
        }
    }

    /**
     * Write [bitmap] as [name] into the document [tree] the user picked
     * ([sub]: into that folder of it, made if need be). Throws whatever
     * stands in the way; leaves no half an image behind.
     */
    private fun saveToTree(context: Context, tree: Uri, bitmap: Bitmap, name: String, sub: String?): Delivered {
        val resolver = context.contentResolver
        var dir = treeRoot(tree)
        if (sub != null) {
            dir = findFolder(context, tree, dir, sub)
                ?: DocumentsContract.createDocument(resolver, dir, DocumentsContract.Document.MIME_TYPE_DIR, sub)
                ?: throw IOException("the folder $sub could not be made")
        }
        // (A name already taken gets a " (1)" from the provider.)
        val doc = DocumentsContract.createDocument(resolver, dir, "image/png", name)
            ?: throw IOException("the folder refused the image")
        try {
            val out = resolver.openOutputStream(doc) ?: throw IOException("the image could not be opened")
            out.use { writePng(bitmap, it) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        // A viewer opens the gallery's entry for it more surely than the
        // document: there is one wherever the folder is on the device.
        val view = runCatching { MediaStore.getMediaUri(context, doc) }.getOrNull() ?: doc
        // Named as the default one is, "Screenshots": by its own name, not
        // its whole path, which the settings show.
        val folder = describeSaveDir(context, tree)?.substringAfterLast('/')?.ifEmpty { null }
            ?: context.getString(R.string.saved_folder_unnamed)
        return Delivered(view, listOfNotNull(folder, sub).joinToString("/"))
    }

    /** The folder called [name] in the folder [parent] of [tree], if
     *  there is one. Names are matched as shared storage matches them,
     *  whatever the case. Throws if the provider can't say: taken for "not
     *  there", that would make a second folder beside the first. */
    private fun findFolder(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        // On the device's own storage a document's ID is its path: the
        // folder can be asked for by name, without listing every image
        // the save folder already holds.
        if (tree.authority == EXTERNAL_STORAGE) {
            val id = if (parentId.endsWith(':')) parentId + name else "$parentId/$name"
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            val mime = runCatching {
                context.contentResolver.query(
                    doc, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return doc
            // Else the listing decides: not there (which the provider says
            // as it says any failure, by throwing), not a folder, or the
            // provider not answering — the listing then fails too, and the
            // save with it.
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val c = context.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("the folder could not be listed")
        c.use {
            while (c.moveToNext()) {
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && c.getString(1).equals(name, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    /** The folder for images of the app [packageName]: its name as the
     *  launcher shows it, or its package name where that can't be had. */
    internal fun appFolder(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrNull()
        return label?.let(::folderName) ?: folderName(packageName)
    }

    /** [label] made fit to name a folder on any storage, a memory card's
     *  too: no characters that FAT forbids, no dots or spaces at the ends,
     *  not overly long. Null if nothing is left of it. */
    internal fun folderName(label: String): String? =
        label.replace(Regex("\\s+"), " ")
            .replace(Regex("\\p{Cntrl}"), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(FOLDER_NAME_MAX)
            // (Not half an emoji: a cut in a surrogate pair is no name.)
            .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            .trim(' ', '.')
            .ifEmpty { null }

    /**
     * The folder [tree] as the user would name it: its path on the
     * device's own storage ("Pictures/Notes"), else the name it has where
     * it is. Null if it can't be looked at.
     */
    fun describeSaveDir(context: Context, tree: Uri): String? = runCatching {
        saveDirPath(tree)?.let { return@runCatching it }
        val doc = treeRoot(tree)
        context.contentResolver.query(
            doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** [tree]'s path, where it is on the device's own storage or a memory
     *  card ("Pictures/Notes"): read off the URI, nothing asked. Else null. */
    fun saveDirPath(tree: Uri): String? = runCatching {
        DocumentsContract.getTreeDocumentId(tree).takeIf { tree.authority == EXTERNAL_STORAGE }
            ?.substringAfter(':', "")?.ifEmpty { null }
    }.getOrNull()

    /** The folder [tree] itself, as a document: what is asked about it,
     *  made in it, and what the picker opens at. Throws if [tree] is none. */
    fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** True if Save can use the folder [tree] the user picked: the grant
     *  for it is still held, and the folder is still there and takes new
     *  files (one a provider keeps read-only would have every save go to
     *  the default folder instead). Asks the folder's provider: not on the
     *  main thread. Never throws: what can't be asked is not usable. */
    fun isSaveDirUsable(context: Context, tree: Uri): Boolean = runCatching {
        val granted = context.contentResolver.persistedUriPermissions
            .any { it.uri == tree && it.isWritePermission }
        granted && context.contentResolver.query(
            treeRoot(tree), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
        )?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        } == true
    }.getOrDefault(false)

    /**
     * Write [bitmap] to [out] as a PNG, or throw. compress() itself doesn't
     * throw when the stream it writes to does — storage running full — but
     * swallows the error and returns false; ignoring that hands a truncated
     * file on as if it were the image.
     */
    @Throws(IOException::class)
    internal fun writePng(bitmap: Bitmap, out: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw IOException("the image could not be written")
        }
    }

    /** Delete shared and copied crops older than an hour — in the
     *  background, behind any Share or Copy still being written. */
    fun cleanupCache(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val dir = File(app.cacheDir, DIR)
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            dir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }
}
