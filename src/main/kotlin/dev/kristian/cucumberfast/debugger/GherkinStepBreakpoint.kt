package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.SourcePosition
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.debugger.ui.breakpoints.LineBreakpoint
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.event.LocatableEvent
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties

/**
 * A breakpoint set on a step in a feature file, installed on the step definition behind it.
 *
 * The JVM has nothing to stop at on a `.feature` line, so this reports the *Java* position of the
 * step definition's first executable line and lets [LineBreakpoint] do the rest — class-prepare
 * requests, locations, lambda bodies, conditions and filters all work as they do for a breakpoint
 * placed in the Java file by hand. Two things are added on top: locations are accepted only in the
 * method that actually holds the definition, and the breakpoint stops only while Cucumber is
 * running *this* step rather than any other step sharing the definition.
 *
 * The pause therefore lands at the top of the step definition, before it does its work — which is
 * where you want to be when a step you are watching is about to run.
 */
internal class GherkinStepBreakpoint(
    project: Project,
    xBreakpoint: XBreakpoint<JavaLineBreakpointProperties>,
) : LineBreakpoint<JavaLineBreakpointProperties>(project, xBreakpoint) {

    @Volatile
    private var target: GherkinStepTarget? = null

    /**
     * Recomputed on every reload, because the step definition a step resolves to changes with the
     * code: renaming a method, rewording a pattern or adding a competing definition all move where
     * this breakpoint belongs. Indexing is left alone — resolution needs the index, and clearing
     * the target while it rebuilds would drop a working breakpoint on a session that starts during
     * indexing.
     */
    override fun reload() {
        if (!DumbService.isDumb(myProject)) {
            val position = xSourcePosition
            target = position?.let { GherkinStepTarget.resolve(myProject, it.file, it.line) }
        }
        super.reload()
    }

    /** The Java position the request goes on, not the feature line the gutter icon sits on. */
    override fun getSourcePosition(): SourcePosition? = target?.position

    override fun getLineIndex(): Int = target?.position?.line ?: -1

    /**
     * The step definition's file, so the scope check in [LineBreakpoint] sees a Java source file.
     * Answering with the feature file would have it decide whether a `.feature` under a resource
     * root belongs to the debug process's scope, which is not a question it knows how to answer.
     */
    override fun getVirtualFile(): VirtualFile? = target?.position?.file?.virtualFile

    /**
     * Reports the breakpoint as invalid when its step defines nothing, instead of letting the base
     * class log an error about a null position. The message reaches the gutter icon's tooltip.
     *
     * A session started while the project is still indexing gets a second chance rather than that
     * message: resolution needs the index, and a breakpoint dropped for the whole run because the
     * IDE happened to be busy would be the worse answer.
     */
    override fun createRequest(debugProcess: DebugProcessImpl) {
        ReadAction.run<RuntimeException> { reload() }
        if (isValid && target == null) {
            if (DumbService.isDumb(myProject)) {
                retryWhenIndexed(debugProcess)
                return
            }
            debugProcess.requestsManager.setInvalid(this, NO_DEFINITION)
            updateUI()
            return
        }
        super.createRequest(debugProcess)
    }

    private fun retryWhenIndexed(debugProcess: DebugProcessImpl) {
        DumbService.getInstance(myProject).runWhenSmart {
            if (debugProcess.isAttached) {
                debugProcess.managerThread.schedule(PrioritizedTask.Priority.HIGH) { createRequest(debugProcess) }
            }
        }
    }

    /**
     * Keeps the request inside the method holding the definition. A line can belong to more than
     * one method — a one-line lambda step definition shares its line with the call registering it —
     * and a request on the wrong one stops while the glue is being loaded.
     */
    override fun acceptLocation(debugProcess: DebugProcessImpl, classType: ReferenceType, loc: Location): Boolean {
        val body = target?.body ?: return super.acceptLocation(debugProcess, classType, loc)
        return body.accepts(loc.method().name()) && super.acceptLocation(debugProcess, classType, loc)
    }

    /** Stops only for the step this breakpoint was put on; see [RunningStep]. */
    override fun evaluateCondition(context: EvaluationContextImpl, event: LocatableEvent): Boolean {
        val identity = target?.identity
        if (identity != null && !RunningStep.isRunning(identity, context)) return false
        return super.evaluateCondition(context, event)
    }

    override fun getDisplayName(): String = label() ?: super.getDisplayName()

    override fun getShortName(): String = label() ?: super.getShortName()

    override fun getEventMessage(event: LocatableEvent): String =
        label()?.let { "Breakpoint reached: $it" } ?: super.getEventMessage(event)

    /** `eat.feature:4 in iHaveCukes()` — the feature line, and where it actually stopped. */
    private fun label(): String? {
        val position = xSourcePosition ?: return null
        val where = "${position.file.name}:${position.line + 1}"
        val definition = target?.definitionName ?: return where
        return "$where in $definition()"
    }

    private val xSourcePosition get() = xBreakpoint?.sourcePosition

    private companion object {
        const val NO_DEFINITION = "No step definition matches this step"
    }
}
