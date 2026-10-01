package dev.landrop.mobile

import java.io.InputStream

/** Check the source itself, since a successful send block may return a nullable ACK result. */
internal inline fun <T> useTransferSource(
    source: InputStream?,
    relativePath: String,
    send: (InputStream) -> T
): T {
    val opened = source ?: error("Could not open $relativePath.")
    return opened.use(send)
}
