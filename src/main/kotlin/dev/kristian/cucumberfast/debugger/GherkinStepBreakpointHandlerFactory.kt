package dev.kristian.cucumberfast.debugger

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaBreakpointHandler
import com.intellij.debugger.engine.JavaBreakpointHandlerFactory

/**
 * Hands feature-file breakpoints to the Java debugger.
 *
 * Every Java debug process asks this extension point what else it should install, which is how a
 * breakpoint type belonging to another language gets JDI requests created for it. The handler
 * itself needs no logic: it names the type, and the platform builds each breakpoint through
 * [GherkinStepBreakpointType.createJavaBreakpoint].
 *
 * Nothing here is tied to how the run was started, so these breakpoints work under any JVM debug
 * session — JUnit, Gradle, Maven or a remote attach — and this plugin does not need to supply a run
 * configuration of its own for them.
 */
class GherkinStepBreakpointHandlerFactory : JavaBreakpointHandlerFactory {

    override fun createHandler(process: DebugProcessImpl): JavaBreakpointHandler =
        JavaBreakpointHandler(GherkinStepBreakpointType::class.java, process)
}
