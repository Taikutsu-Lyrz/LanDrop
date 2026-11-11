package dev.landrop.mobile

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DownloadsDestinationTest {
    @Test fun streamsResumesAndPublishesInDownloadsWithoutFolderGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val destination = DownloadsDestination(context)
        val directory = "storage-test-${UUID.randomUUID()}"
        val path = "$directory/hello.txt"
        val uri = destination.createPartial(path, "Documents")
        var secondUri: android.net.Uri? = null
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write("hello ".toByteArray()) }
            assertEquals(uri, destination.findPartial(path, "Documents"))
            assertEquals(6L, documentByteSize(resolver, uri))
            resolver.openOutputStream(uri, "wa")!!.use { it.write("LanDrop".toByteArray()) }
            destination.finish(uri, path, "Documents")
            assertNull(destination.findPartial(path, "Documents"))
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING),
                null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("hello.txt", cursor.getString(0))
                assertEquals("Download/LanDrop/Documents/$directory/", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }
            assertEquals("hello LanDrop", resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })

            // Verify receiving a second file with the same name does not fail on MediaStore uniqueness.
            secondUri = destination.createPartial(path, "Documents")
            resolver.openOutputStream(secondUri, "w")!!.use { it.write("second copy".toByteArray()) }
            destination.finish(secondUri, path, "Documents")
            assertEquals("second copy", resolver.openInputStream(secondUri)!!.bufferedReader().use { it.readText() })
        } finally {
            secondUri?.let { resolver.delete(it, null, null) }
            resolver.delete(uri, null, null)
        }
    }
}
