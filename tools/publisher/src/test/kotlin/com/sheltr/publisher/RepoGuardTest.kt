package com.sheltr.publisher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RepoGuardTest {

    // Both trees live under the system temp directory, so neither is inside the real repository.
    private lateinit var scratch: Path
    private lateinit var fakeRepo: Path
    private lateinit var outside: Path

    @BeforeTest
    fun setUp() {
        scratch = createTempDirectory("repo-guard-")
        fakeRepo = scratch.resolve("fake-repo").createDirectories()
        fakeRepo.resolve(REPO_MARKER).writeText("rootProject.name = \"fake\"\n")
        fakeRepo.resolve("tools/publisher/src").createDirectories()
        outside = scratch.resolve("outside").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        scratch.toFile().deleteRecursively()
    }

    @Test
    fun findsTheRootByWalkingUpFromASubdirectory() {
        val start = fakeRepo.resolve("tools/publisher/src")

        assertEquals(fakeRepo.toRealPath(), findRepoRoot(start))
    }

    @Test
    fun noRepositoryMeansNoRestriction() {
        assertNull(findRepoRoot(outside))
        assertNull(keyInsideRepository(outside.resolve("key.der"), outside))
    }

    @Test
    fun refusesAKeyInsideTheRepository() {
        val key = fakeRepo.resolve("tools/publisher/key.der").also { it.writeText("k") }

        val message = assertNotNull(keyInsideRepository(key, fakeRepo.resolve("tools/publisher")))
        assertContains(message, "inside the repository")
    }

    @Test
    fun refusesAKeyPathInsideTheRepositoryEvenBeforeTheFileExists() {
        val key = fakeRepo.resolve("secrets/new-key.der")

        assertNotNull(keyInsideRepository(key, fakeRepo))
    }

    @Test
    fun refusesASymlinkFromOutsideIntoTheRepository() {
        val target = fakeRepo.resolve("key.der").also { it.writeText("k") }
        val link = outside.resolve("link.der").createSymbolicLinkPointingTo(target)

        assertNotNull(keyInsideRepository(link, fakeRepo))
    }

    @Test
    fun allowsAKeyOutsideTheRepository() {
        val key = outside.resolve("key.der").also { it.writeText("k") }

        assertNull(keyInsideRepository(key, fakeRepo.resolve("tools/publisher")))
    }

    @Test
    fun allowsAKeyNextToTheRepositoryNotInsideIt() {
        // A sibling whose name starts with the repository name is not inside it.
        val sibling = scratch.resolve("fake-repo-sibling").createDirectories()
        val key = sibling.resolve("key.der").also { it.writeText("k") }

        assertNull(keyInsideRepository(key, fakeRepo))
    }
}
