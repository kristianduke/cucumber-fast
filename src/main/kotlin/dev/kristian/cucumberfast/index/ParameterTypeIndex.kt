package dev.kristian.cucumberfast.index

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * Project-defined `@ParameterType` declarations, keyed by the name a Cucumber expression uses.
 *
 * Without these, an expression such as `I have a {colour} cuke` cannot be turned into a working
 * regex at all: IntelliJ leaves the unresolved `{colour}` in the pattern, where it matches nothing,
 * and drops the pattern into the catch-all bucket. Resolving them fixes the match *and* moves the
 * pattern into its proper bucket.
 */
class ParameterTypeIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getIndexer(): DataIndexer<String, String, FileContent> =
        DataIndexer { content ->
            JavaStepDefinitionScanner.scan(content.contentAsText)
                .parameterTypes
                .associate { it.name to it.regex }
        }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = Externalizer

    override fun getVersion(): Int = VERSION

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(JavaFileType.INSTANCE)

    override fun dependsOnFileContent(): Boolean = true

    private object Externalizer : DataExternalizer<String> {
        override fun save(out: DataOutput, value: String) = EnumeratorStringDescriptor.INSTANCE.save(out, value)
        override fun read(input: DataInput): String = EnumeratorStringDescriptor.INSTANCE.read(input)
    }

    companion object {
        val NAME: ID<String, String> = ID.create("cucumberfast.java.parameter.types")

        private const val VERSION = 1
    }
}
