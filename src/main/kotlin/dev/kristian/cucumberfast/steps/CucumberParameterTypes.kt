package dev.kristian.cucumberfast.steps

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import dev.kristian.cucumberfast.expression.StandardParameterTypes
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.ParameterTypeIndex
import org.jetbrains.plugins.cucumber.ParameterTypeManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the parameter types a Cucumber expression refers to: Cucumber's built-ins first, then
 * the project's own `@ParameterType` declarations.
 *
 * Patterns that use only built-ins are compiled once application-wide; only the ones naming a
 * project-defined type get a project-specific regex, cached until the declarations change.
 */
@Service(Service.Level.PROJECT)
class CucumberParameterTypes(private val project: Project) : ParameterTypeManager {

    override fun getParameterTypeValue(name: String): String {
        StandardParameterTypes.getParameterTypeValue(name)?.let { return it }
        declarations()[name]?.let { return it }
        // An unknown type would otherwise leave `{name}` in the regex, where it matches nothing.
        // Matching permissively keeps navigation working for a type this plugin cannot see; the
        // runtime would refuse the step outright, which the user finds out soon enough.
        return "(.*)"
    }

    override fun getParameterTypeDeclaration(name: String): PsiElement? = null

    /** The pattern for [source], with project-defined parameter types resolved if it uses any. */
    fun patternFor(source: String): StepPattern {
        val base = StepPattern.compile(source)
        if (!base.usesCustomParameterTypes) return base
        return resolvedPatterns().getOrPut(source) { base.withParameterTypes(this) }
    }

    private fun declarations(): Map<String, String> {
        if (DumbService.isDumb(project)) return emptyMap()
        return CachedValuesManager.getManager(project).getCachedValue(project, DECLARATIONS, {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.allScope(project)
            val result = HashMap<String, String>()
            for (name in index.getAllKeys(ParameterTypeIndex.NAME, project)) {
                index.getValues(ParameterTypeIndex.NAME, name, scope).firstOrNull()?.let { result[name] = it }
            }
            CachedValueProvider.Result.create<Map<String, String>>(result, indexTracker())
        }, false)
    }

    private fun resolvedPatterns(): ConcurrentHashMap<String, StepPattern> =
        CachedValuesManager.getManager(project).getCachedValue(project, RESOLVED_PATTERNS, {
            CachedValueProvider.Result.create(ConcurrentHashMap<String, StepPattern>(), indexTracker())
        }, false)

    private fun indexTracker(): ModificationTracker = ModificationTracker {
        FileBasedIndex.getInstance().getIndexModificationStamp(ParameterTypeIndex.NAME, project)
    }

    companion object {
        private val DECLARATIONS =
            Key.create<CachedValue<Map<String, String>>>("cucumberfast.parameter.type.declarations")
        private val RESOLVED_PATTERNS =
            Key.create<CachedValue<ConcurrentHashMap<String, StepPattern>>>("cucumberfast.resolved.patterns")

        fun getInstance(project: Project): CucumberParameterTypes = project.service()
    }
}
