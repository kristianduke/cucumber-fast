package dev.kristian.cucumberfast.index

import com.intellij.ide.highlighter.JavaFileType
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
import java.io.DataInput
import java.io.DataOutput

/**
 * Java step definitions, bucketed by the first one or two literal words of their pattern.
 *
 * The bucket is what keeps step resolution off the linear path: a step only has to be compared
 * against the definitions in its own bucket plus [StepPattern.ANY_KEY], instead of against every
 * definition in the project.
 */
class JavaStepDefinitionIndex : FileBasedIndexExtension<String, List<StepDefinitionEntry>>() {

    override fun getName(): ID<String, List<StepDefinitionEntry>> = NAME

    override fun getIndexer(): DataIndexer<String, List<StepDefinitionEntry>, FileContent> =
        DataIndexer { content ->
            JavaStepDefinitionScanner.scan(content.contentAsText)
                .groupBy { StepPattern.compile(it.expression).indexKey }
        }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<List<StepDefinitionEntry>> = Externalizer

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(JavaFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    private object Externalizer : DataExternalizer<List<StepDefinitionEntry>> {
        override fun save(out: DataOutput, value: List<StepDefinitionEntry>) {
            DataInputOutputUtil.writeINT(out, value.size)
            for (entry in value) {
                IOUtil.writeUTF(out, entry.annotationName)
                IOUtil.writeUTF(out, entry.expression)
                DataInputOutputUtil.writeINT(out, entry.annotationOffset)
            }
        }

        override fun read(input: DataInput): List<StepDefinitionEntry> {
            val size = DataInputOutputUtil.readINT(input)
            val result = ArrayList<StepDefinitionEntry>(size)
            repeat(size) {
                val annotationName = IOUtil.readUTF(input)
                val expression = IOUtil.readUTF(input)
                val offset = DataInputOutputUtil.readINT(input)
                result.add(StepDefinitionEntry(annotationName, expression, offset))
            }
            return result
        }
    }

    companion object {
        val NAME: ID<String, List<StepDefinitionEntry>> = ID.create("cucumberfast.java.step.definitions")

        /** Bump whenever the scanner, the key scheme or the serialized form changes. */
        private const val VERSION = 1
    }
}
