package dev.landrop.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiveDestinationPathTest {
    @Test fun customParentGetsOneLanDropDirectory() {
        assertEquals(listOf("LanDrop", "Images", "Trip", "photo.jpg"), receivePathParts("Trip/photo.jpg", "Images", "FolderA"))
    }
    @Test fun selectedLanDropIsUsedDirectly() {
        assertEquals(listOf("Images", "photo.jpg"), receivePathParts("photo.jpg", "Images", "LanDrop"))
        assertEquals(listOf("Files", "hello.txt"), receivePathParts("hello.txt", "Files", "landrop"))
    }
    @Test fun downloadsKeepsFoldersWithoutStaging() {
        assertEquals(listOf("LanDrop", "Files", "project", "src", "main.kt"), receivePathParts("project/src/main.kt", "Files", "Download"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsParentTraversal() {
        receivePathParts("../secret.txt", "Files", "Download")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyPath() {
        receivePathParts("", "Files", "Download")
    }
}
