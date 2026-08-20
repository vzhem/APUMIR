package com.vladimir.messenger.data.file

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns

/** Storage Access Framework boundary. Provider URI/path is never logged or persisted here. */
object AndroidFileSelection {
    fun inspect(resolver: ContentResolver, uri: Uri): FileTransferSourceInspector.InspectedFile {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only content URIs are accepted" }
        val metadata = queryMetadata(resolver, uri)
        return FileTransferSourceInspector.inspect(
            providerDisplayName = metadata.displayName,
            providerMediaType = resolver.getType(uri),
            declaredSize = metadata.size,
        ) {
            resolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open selected file")
        }
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): ProviderMetadata {
        val cursor = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        ) ?: return ProviderMetadata(null, null)
        return cursor.use {
            if (!it.moveToFirst()) return@use ProviderMetadata(null, null)
            ProviderMetadata(
                displayName = it.optionalString(OpenableColumns.DISPLAY_NAME),
                size = it.optionalLong(OpenableColumns.SIZE),
            )
        }
    }

    private fun Cursor.optionalString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        val value = getString(index)
        return value.take(FileTransferSourceInspector.MAX_PROVIDER_NAME_CHARS)
    }

    private fun Cursor.optionalLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index).takeIf { it >= 0 }
    }

    private data class ProviderMetadata(val displayName: String?, val size: Long?)
}
