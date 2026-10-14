package com.vladimir.messenger.data.schedule

/** Pure decisions: neither a transport ACK nor an unrelated peer can confirm hidden storage. */
object DeferredDeliveryPolicy {
    fun acceptsReceipt(
        receipt: DeferredWire.Receipt, scheduleId: String, owner: String, currentOwner: String,
        recipients: List<String>, sender: String, wire: String,
    ): Boolean = owner.isNotEmpty() && owner == currentOwner &&
        receipt.id == "deferred_$scheduleId" && sender in recipients &&
        DeferredWire.digest(wire) == receipt.digest

    /** Bounded round-robin: offline recipients cannot starve the rest of a large group. */
    fun batch(recipients: List<String>, cursor: Int, skip: List<String>, limit: Int = 8): List<Pair<Int, String>> {
        if (recipients.isEmpty()) return emptyList()
        val start = cursor.coerceAtLeast(0) % recipients.size
        return (recipients.indices).map { (start + it) % recipients.size }
            .filter { recipients[it] !in skip }.take(limit).map { it to recipients[it] }
    }

    fun needsFallback(peer: String, confirmed: List<String>, handedOff: List<String>): Boolean =
        peer !in confirmed && peer !in handedOff
}
