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

    private val MODULE_BUCKETS =
        Key.create<com.intellij.psi.util.CachedValue<Map<String, List<IndexedJavaStepDefinition>>>>(
            "cucumberfast.module.buckets",
        )

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

    /**
     * The definitions that match [stepText], found through its buckets rather than by asking every
     * definition in the module.
     *
     * This filters the cached module list rather than querying the index again. Going back to the
     * index per lookup means rebuilding a step definition — and with it a smart pointer — for every
     * candidate, every time; measured against a synthetic suite that was several times *slower*
     * than the linear pass it was supposed to beat.
     */
    fun definitionsForStep(module: Module, stepText: String): List<IndexedJavaStepDefinition> {
        val buckets = buckets(module)
        if (buckets.isEmpty()) return emptyList()

        var result: MutableList<IndexedJavaStepDefinition>? = null
        for (key in StepPattern.lookupKeysForStepText(stepText)) {
            val candidates = buckets[key] ?: continue
            for (candidate in candidates) {
                if (!candidate.matches(stepText)) continue
                (result ?: ArrayList<IndexedJavaStepDefinition>(2).also { result = it }).add(candidate)
            }
        }
        return result ?: emptyList()
    }

    /**
     * The module's definitions grouped by the bucket their pattern belongs to, so a step reaches
     * only the handful that could match it. Derived from — and invalidated with — [allDefinitions].
     */
    private fun buckets(module: Module): Map<String, List<IndexedJavaStepDefinition>> {
        val project = module.project
        if (DumbService.isDumb(project)) return emptyMap()
        return CachedValuesManager.getManager(project).getCachedValue(module, MODULE_BUCKETS, {
            val grouped = allDefinitions(module).groupBy { it.pattern.indexKey }
            CachedValueProvider.Result.create(grouped, indexTracker(project))
        }, false)
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
