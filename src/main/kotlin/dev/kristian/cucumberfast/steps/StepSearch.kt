package dev.kristian.cucumberfast.steps

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import dev.kristian.cucumberfast.expression.StepPattern
import dev.kristian.cucumberfast.index.GherkinStepEntry
import dev.kristian.cucumberfast.index.GherkinStepIndex
import dev.kristian.cucumberfast.index.JavaStepDefinitionIndex

/** Index queries in both directions: step text to definitions, and pattern to feature steps. */
object StepSearch {

    private val MODULE_DEFINITIONS =
        Key.create<com.intellij.psi.util.CachedValue<List<IndexedJavaStepDefinition>>>("cucumberfast.module.definitions")

    /**
     * Every step definition visible from [module].
     *
     * IntelliJ's step resolution asks for the whole set and then filters it, so the list is cached
     * against the index rather than against `PsiModificationTracker`: editing a feature file, or
     * any file without step definitions, reuses it instead of rebuilding it.
     */
    fun allDefinitions(module: Module): List<IndexedJavaStepDefinition> {
        val project = module.project
        if (DumbService.isDumb(project)) return emptyList()
        return CachedValuesManager.getManager(project).getCachedValue(module, MODULE_DEFINITIONS, {
            val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
            CachedValueProvider.Result.create(collectDefinitions(project, scope), indexTracker(project))
        }, false)
    }

    /** The definitions that could match [stepText] — its own buckets plus the catch-all bucket. */
    fun definitionsForStep(project: Project, scope: GlobalSearchScope, stepText: String): List<IndexedJavaStepDefinition> {
        if (DumbService.isDumb(project)) return emptyList()
        val index = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val result = ArrayList<IndexedJavaStepDefinition>()
        for (key in StepPattern.lookupKeysForStepText(stepText)) {
            index.processValues(JavaStepDefinitionIndex.NAME, key, null, { file, entries ->
                val psiFile = psiManager.findFile(file)
                if (psiFile != null) {
                    entries.mapTo(result) { IndexedJavaStepDefinition(psiFile, it) }
                }
                true
            }, scope)
        }
        return result.filter { it.matches(stepText) }
    }

    /** The feature steps [pattern] defines, as (file, step) pairs — no Gherkin PSI is built. */
    fun featureStepsFor(project: Project, scope: GlobalSearchScope, pattern: StepPattern): List<Pair<VirtualFile, GherkinStepEntry>> {
        if (DumbService.isDumb(project)) return emptyList()
        val index = FileBasedIndex.getInstance()
        val keys = if (pattern.indexKey == StepPattern.ANY_KEY) {
            // The pattern starts with a placeholder, so no bucket narrows it down.
            index.getAllKeys(GherkinStepIndex.NAME, project)
        } else {
            listOf(pattern.indexKey)
        }

        val result = ArrayList<Pair<VirtualFile, GherkinStepEntry>>()
        for (key in keys) {
            index.processValues(GherkinStepIndex.NAME, key, null, { file, entries ->
                for (entry in entries) {
                    if (pattern.matches(entry.text)) result.add(file to entry)
                }
                true
            }, scope)
        }
        return result.distinctBy { (file, entry) -> file to entry.offset }
    }

    /** Java files holding at least one step definition, for "create step definition" targets. */
    fun definitionContainers(module: Module): Collection<PsiFile> =
        allDefinitions(module).mapTo(LinkedHashSet()) { it.containingFile }

    private fun collectDefinitions(project: Project, scope: GlobalSearchScope): List<IndexedJavaStepDefinition> {
        val index = FileBasedIndex.getInstance()
        val psiManager = PsiManager.getInstance(project)
        val result = ArrayList<IndexedJavaStepDefinition>()
        for (key in index.getAllKeys(JavaStepDefinitionIndex.NAME, project)) {
            index.processValues(JavaStepDefinitionIndex.NAME, key, null, { file, entries ->
                val psiFile = psiManager.findFile(file)
                if (psiFile != null) {
                    entries.mapTo(result) { IndexedJavaStepDefinition(psiFile, it) }
                }
                true
            }, scope)
        }
        return result
    }

    private fun indexTracker(project: Project): ModificationTracker = ModificationTracker {
        FileBasedIndex.getInstance().getIndexModificationStamp(JavaStepDefinitionIndex.NAME, project)
    }
}
