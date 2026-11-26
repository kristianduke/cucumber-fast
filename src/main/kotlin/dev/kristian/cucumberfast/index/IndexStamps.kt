package dev.kristian.cucumberfast.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import java.util.concurrent.ConcurrentHashMap

/**
 * Modification trackers for this plugin's indexes, for caches that should survive any change that
 * leaves a given index alone, such as editing a feature file without touching step definitions.
 *
 * `FileBasedIndex.getIndexModificationStamp` is exact, but it brings the index up to date across
 * the whole project before it answers. That costs about 9 µs a call, and a cached value calls its
 * tracker on every read, so on the resolution path that check was costing more than the lookup it
 * guarded. Here the stamp is asked for only when something it depends on could have moved:
 *
 *  - a PSI change, which covers every committed edit in an open file;
 *  - a VFS change, which covers files changed on disk, created, deleted or moved;
 *  - a change of project roots, which changes what "the whole project" means;
 *  - entering or leaving dumb mode, during which the index fills in without any of the above.
 *
 * While any document is uncommitted the stamp is always asked for. The index reads unsaved document
 * text, and an edit nobody has committed yet has not reached the PSI counter.
 *
 * If none of these has moved since the last check, the index has nothing new to take in, so the
 * stamp it would report is the one recorded last time.
 */
@Service(Service.Level.PROJECT)
class IndexStamps(private val project: Project) {

    private val trackers = ConcurrentHashMap<List<ID<*, *>>, ModificationTracker>()

    /**
     * One tracker for all of [indexIds], changing when any of them does. A cache that depends on
     * several indexes should use this rather than one tracker each: they would all read the same
     * inputs, and the check runs on every read of the cache.
     */
    fun tracker(vararg indexIds: ID<*, *>): ModificationTracker =
        trackers.computeIfAbsent(indexIds.toList()) { GatedStamp(project, it) }

    private class GatedStamp(private val project: Project, private val indexIds: List<ID<*, *>>) : ModificationTracker {

        private class Snapshot(val inputs: LongArray, val stamp: Long)

        @Volatile
        private var last: Snapshot? = null

        override fun getModificationCount(): Long {
            // Read before asking for the stamp. If something changes in between, the recorded
            // inputs are already out of date, so the next call checks again and never misses it.
            val inputs = inputs()
            val previous = last
            if (previous != null && previous.inputs.contentEquals(inputs) &&
                !PsiDocumentManager.getInstance(project).hasUncommitedDocuments()
            ) {
                return previous.stamp
            }
            // Each index's stamp only grows, so their sum grows whenever any one of them does.
            val index = FileBasedIndex.getInstance()
            val stamp = indexIds.sumOf { index.getIndexModificationStamp(it, project) }
            last = Snapshot(inputs, stamp)
            return stamp
        }

        private fun inputs() = longArrayOf(
            PsiModificationTracker.getInstance(project).modificationCount,
            VirtualFileManager.getInstance().modificationCount,
            ProjectRootManager.getInstance(project).modificationCount,
            DumbService.getInstance(project).modificationTracker.modificationCount,
        )
    }

    companion object {
        fun getInstance(project: Project): IndexStamps = project.service()

        fun tracker(project: Project, vararg indexIds: ID<*, *>): ModificationTracker =
            getInstance(project).tracker(*indexIds)
    }
}
