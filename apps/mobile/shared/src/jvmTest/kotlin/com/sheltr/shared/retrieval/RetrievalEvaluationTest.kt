package com.sheltr.shared.retrieval

import com.sheltr.shared.store.BundleAssetReader
import com.sheltr.shared.store.ContentStore
import com.sheltr.shared.store.JdbcStoreDriverFactory
import com.sheltr.shared.store.installSampleBundleIfEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures keyword retrieval on the shipped sample bundle with a fixed question set
 * (src/jvmTest/resources/retrieval-eval.tsv). It is a regression gate for the two properties that matter:
 *
 * - false accepts (an off-topic question that gets an answer) must stay at zero: the app must refuse rather than
 *   present unrelated guidance;
 * - at least 90% of answerable questions must be answered, with the passage that contains the expected term in the
 *   top results. A refusal is the safe failure, a wrong answer is not.
 *
 * Grow the question set whenever the corpus grows. The set is small: it guards against regressions, it does not
 * prove quality on questions nobody has thought of.
 */
class RetrievalEvaluationTest {

    private data class Case(val answerable: Boolean, val country: String?, val question: String, val term: String)

    private lateinit var dir: Path
    private lateinit var store: ContentStore
    private lateinit var retriever: Retriever

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sheltr-eval")
        store = ContentStore(dir.toString(), JdbcStoreDriverFactory)
        installSampleBundleIfEmpty(store, SampleAssets)
        retriever = Retriever(store)
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun loadCases(): List<Case> =
        checkNotNull(javaClass.getResourceAsStream("/retrieval-eval.tsv")).bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t')
                Case(parts[0] == "answerable", parts[1].ifEmpty { null }, parts[2], parts.getOrElse(3) { "" })
            }

    @Test
    fun noOffTopicQuestionIsAnswered() {
        val falseAccepts = loadCases().filter { !it.answerable }.filter {
            retriever.retrieve(RetrievalQuery(it.question, it.country)) is RetrievalResult.Passages
        }
        assertTrue(falseAccepts.isEmpty(), "false accepts: ${falseAccepts.map { it.question }}")
    }

    @Test
    fun answerableQuestionsAreMostlyAnsweredWithTheExpectedPassage() {
        val cases = loadCases().filter { it.answerable }
        val refused = mutableListOf<String>()
        val missed = mutableListOf<String>()
        for (case in cases) {
            val result = retriever.retrieve(RetrievalQuery(case.question, case.country))
            if (result !is RetrievalResult.Passages) {
                refused += case.question
            } else if (result.passages.none { it.chunk.content.contains(case.term, ignoreCase = true) }) {
                missed += case.question
            }
        }
        val answered = cases.size - refused.size
        val found = answered - missed.size
        println("EVAL answerable=${cases.size} answered=$answered foundExpectedPassage=$found")
        println("EVAL refused: $refused")
        println("EVAL missed: $missed")
        // Refusing is the safe failure, so the bars are below 100%. They match what docs/evaluation.md reports;
        // raise them when retrieval improves, and never lower them to make a change pass.
        assertTrue(answered >= 0.9 * cases.size, "answered $answered of ${cases.size}; refused: $refused")
        assertTrue(found >= 0.9 * cases.size, "found the expected passage for $found of ${cases.size}; missed: $missed")
    }

    private object SampleAssets : BundleAssetReader {
        private val bundleDir = Path.of("..", "android-sample-bundle")
        override fun readManifestJson(): String = bundleDir.resolve("bundle.json").readText()
        override fun readDocumentJson(documentId: String): ByteArray =
            bundleDir.resolve("documents").resolve("$documentId.json").readBytes()
    }
}
