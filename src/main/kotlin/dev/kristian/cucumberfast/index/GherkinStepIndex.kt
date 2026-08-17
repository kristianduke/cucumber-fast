package dev.kristian.cucumberfast.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.DataInputOutputUtil
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import dev.kristian.cucumberfast.expression.StepPattern
import org.jetbrains.plugins.cucumber.psi.GherkinFileType
import java.io.DataInput
import java.io.DataOutput

/**
 * Gherkin steps, bucketed by their first one and first two words.
 *
 * This is the reverse direction: given a step definition pattern, its bucket names the feature
 * steps that could match it, so the gutter marker on a step definition method never has to open
 * every `.feature` file in the project.
 */
class GherkinStepIndex : FileBasedIndexExtension<String, List<GherkinStepEntry>>() {

    override fun getName(): ID<String, List<GherkinStepEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<GherkinStepEntry>, FileContent> =
        DataIndexer { content ->
            val result = HashMap<String, MutableList<GherkinStepEntry>>()
            for (step in GherkinStepScanner.scan(content.contentAsText)) {
                for (key in StepPattern.indexKeysForStepText(step.text)) {
                    result.getOrPut(key) { ArrayList() }.add(step)
                }
            }
            result
        }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<GherkinStepEntry>> = Externalizer

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(GherkinFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    private object Externalizer : DataExternalizer<List<GherkinStepEntry>> {
        override fun save(out: DataOutput, value: List<GherkinStepEntry>) {
            DataInputOutputUtil.writeINT(out, value.size)
            for (entry in value) {
                IOUtil.writeUTF(out, entry.text)
                DataInputOutputUtil.writeINT(out, entry.offset)
            }
        }

        override fun read(input: DataInput): List<GherkinStepEntry> {
            val size = DataInputOutputUtil.readINT(input)
            val result = ArrayList<GherkinStepEntry>(size)
            repeat(size) {
                val text = IOUtil.readUTF(input)
                val offset = DataInputOutputUtil.readINT(input)
                result.add(GherkinStepEntry(text, offset))
            }
            return result
        }
    }

    companion object {
        val NAME: ID<String, List<GherkinStepEntry>> = ID.create("cucumberfast.gherkin.steps")

        private const val VERSION = 1
    }
}
