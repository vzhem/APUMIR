package com.vladimir.messenger.data.update
import android.content.Context
import java.io.File
class ApkUpdateStore(file: File) {
    data class Offer(val nodeId: String, val version: String, val atMs: Long)
    data class PatchOffer(val nodeId: String, val toVersion: String, val atMs: Long)
    fun loadOffers(): List<Offer> = emptyList()
    fun loadPatchOffers(): List<PatchOffer> = emptyList()
}
object UpdateFileRoots { fun seed(context: Context) = File(".") }
object ApkDiffPatch {
    fun apply(base: File, patch: File, out: File) = false
    fun sha256OfFile(file: File) = ""
}
