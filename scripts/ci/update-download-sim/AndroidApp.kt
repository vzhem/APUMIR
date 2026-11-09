package android.app
import android.database.Cursor
import android.net.Uri
open class DownloadManager {
    companion object {
        const val STATUS_PENDING=1; const val STATUS_RUNNING=2; const val STATUS_PAUSED=4; const val STATUS_SUCCESSFUL=8; const val STATUS_FAILED=16
        const val PAUSED_WAITING_TO_RETRY=1; const val PAUSED_WAITING_FOR_NETWORK=2
        const val ERROR_UNKNOWN=1000; const val ERROR_FILE_ERROR=1001; const val ERROR_UNHANDLED_HTTP_CODE=1002
        const val ERROR_HTTP_DATA_ERROR=1004; const val ERROR_TOO_MANY_REDIRECTS=1005; const val ERROR_INSUFFICIENT_SPACE=1006
        const val ERROR_DEVICE_NOT_FOUND=1007; const val ERROR_CANNOT_RESUME=1008; const val ERROR_FILE_ALREADY_EXISTS=1009
        const val COLUMN_ID="_id"; const val COLUMN_TITLE="title"; const val COLUMN_URI="uri"; const val COLUMN_STATUS="status"
        const val COLUMN_DESCRIPTION="description"; const val COLUMN_REASON="reason"; const val COLUMN_LOCAL_URI="local_uri"
    }
    open fun enqueue(request: Request): Long = error("not implemented")
    open fun query(query: Query): Cursor? = error("not implemented")
    open fun remove(vararg ids: Long): Int = error("not implemented")
    class Query {
        var ids: LongArray? = null
        fun setFilterById(vararg id: Long): Query { ids=id; return this }
    }
    class Request(val uri: Uri) {
        companion object { const val VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1 }
        var title: String = ""
        var description: String = ""
        fun setTitle(value: CharSequence): Request { title=value.toString(); return this }
        fun setDescription(value: CharSequence): Request { description=value.toString(); return this }
        fun setMimeType(value: String): Request = this
        fun setNotificationVisibility(value: Int): Request = this
        fun setAllowedOverMetered(value: Boolean): Request = this
        fun setDestinationInExternalPublicDir(dirType: String, subPath: String): Request = this
    }
}
