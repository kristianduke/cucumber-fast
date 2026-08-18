package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.openapi.diagnostic.Logger
import com.sun.jdi.IntegerValue
import com.sun.jdi.ObjectReference
import com.sun.jdi.StringReference
import com.sun.jdi.Value

/**
 * Asks the running JVM which Gherkin step it is executing.
 *
 * A breakpoint on a feature line becomes a JVM breakpoint inside the step definition, and a step
 * definition is normally shared: without this check, a breakpoint on one `Then the user is signed
 * in` would stop on every other step using the same definition, in every scenario of the run.
 *
 * Cucumber holds the answer in the frame that invokes the definition —
 * `io.cucumber.core.runner.PickleStepDefinitionMatch`, which keeps the feature file's URI and the
 * step it is about. Both are read off that frame's receiver.
 *
 * Everything here is best-effort and fails *open*: a Cucumber old enough — or arranged differently
 * enough — that the frame is not recognised leaves the breakpoint behaving as an ordinary one on
 * the step definition, which is the behaviour this replaces. Refusing to stop would be worse than
 * stopping too often.
 */
internal object RunningStep {

    private val LOG = Logger.getInstance(RunningStep::class.java)

    /**
     * How far up the stack to look. Between the step definition and Cucumber's runner sit the
     * backend's invoker and, for annotated methods, reflection — a handful of frames. Twenty is
     * well past that and keeps the scan bounded on a deep stack.
     */
    private const val FRAMES_TO_SCAN = 20

    private const val MATCH_CLASS_SUFFIX = ".PickleStepDefinitionMatch"

    /** Whether the step Cucumber is currently running is the one [identity] describes. */
    fun isRunning(identity: RunningStepIdentity, context: EvaluationContextImpl): Boolean {
        val thread = context.frameProxy?.threadProxy() ?: return true
        return try {
            val depth = minOf(thread.frameCount(), FRAMES_TO_SCAN)
            for (index in 1 until depth) {
                val match = thread.frame(index)
                    ?.takeIf { it.location().declaringType().name().endsWith(MATCH_CLASS_SUFFIX) }
                    ?.thisObject()
                    ?: continue
                return matches(identity, match, context)
            }
            true
        } catch (e: Exception) {
            LOG.debug("Could not determine the running Cucumber step", e)
            true
        }
    }

    private fun matches(identity: RunningStepIdentity, match: ObjectReference, context: EvaluationContextImpl): Boolean {
        val type = match.referenceType()
        val uri = type.fieldByName("uri")?.let { match.getValue(it) } as? ObjectReference ?: return true
        val step = type.fieldByName("step")?.let { match.getValue(it) } as? ObjectReference ?: return true

        val line = (invoke(step, "getLine", "()I", context) as? IntegerValue)?.value() ?: return true
        val uriText = (invoke(uri, "toString", "()Ljava/lang/String;", context) as? StringReference)?.value()
            ?: return true

        return identity.matches(uriText, line)
    }

    private fun invoke(
        target: ObjectReference,
        name: String,
        signature: String,
        context: EvaluationContextImpl,
    ): Value? {
        val method = target.referenceType().methodsByName(name, signature).firstOrNull() ?: return null
        return context.debugProcess.invokeMethod(context, target, method, emptyList())
    }
}
