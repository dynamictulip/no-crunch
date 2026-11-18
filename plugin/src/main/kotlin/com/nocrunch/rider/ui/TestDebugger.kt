package com.nocrunch.rider.ui

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.nocrunch.rider.NoCrunchService
import com.nocrunch.rider.debug.RiderDebugLauncher
import com.nocrunch.rider.debug.TestFilter
import com.nocrunch.rider.engine.TestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Which test "Break into First Failing Test" debugs. */
object DebugTarget {
    /** Debuggable by name (class and method) or by location (file and line). */
    fun canDebug(t: TestNode): Boolean = (t.dto.type != null && t.dto.method != null) || (t.dto.filePath != null && t.dto.line != null)

    /** The first failing test (current or stale result) that can be debugged, in the order given. */
    fun firstFailing(tests: Sequence<TestNode>): TestNode? = tests.firstOrNull { it.shownOutcome == TestState.FAILED && canDebug(it) }
}

/** Debugs a test from the tool window. */
internal class TestDebugger(private val project: Project, private val service: NoCrunchService) {
    /** Debugs [node] by name with a temporary breakpoint on its first line, so the debugger stops as the test starts. */
    fun breakInto(node: TestNode) {
        setTemporaryBreakpointAtStart(node)
        debugTest(node)
    }

    /** A breakpoint on the test's first statement that removes itself once hit. Leaves an existing breakpoint alone. */
    private fun setTemporaryBreakpointAtStart(node: TestNode) {
        val path = node.dto.filePath ?: return
        val line = node.dto.line ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val bodyLine = TestLocator.firstBodyLine(document.charsSequence, line - 1, node.dto.method?.substringBefore('(')) ?: return
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val exists = manager.allBreakpoints.any { it is XLineBreakpoint<*> && it.fileUrl == file.url && it.line == bodyLine }
        if (!exists) XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, bodyLine, true)
    }

    /**
     * Debugs the test by name with a run configuration filtered to it. If that isn't possible (no class/method, or
     * Rider's run-configuration classes have changed), falls back to Rider's caret-based Debug action.
     */
    private fun debugTest(node: TestNode) {
        val d = node.dto
        val root = service.repoRoot
        if (root != null && d.type != null && d.method != null) {
            try {
                val projectFile = java.nio.file.Path.of(root, d.project)
                val args = TestFilter.args(TestFilter.detect(projectFile), d.type, d.method)
                val name = "Debug ${d.type.substringAfterLast('.')}.${d.method.substringBefore('(')}"
                RiderDebugLauncher.debug(project, projectFile, args, name)
                return
            } catch (e: Throwable) {
                if (e is ProcessCanceledException) throw e
                LOG.warn("Debug by name failed for ${d.key}; falling back to Rider's caret-based action", e)
            }
        }
        debugInRider(node)
    }

    /**
     * Fallback: Rider's "Debug" context action works out which test to debug from the editor caret, so open the test's file,
     * put the caret on the test method's name, and run that action on the editor. The action is answered by Rider's
     * backend, which needs a moment to see the newly opened editor and caret, so keep trying for a few seconds.
     */
    private fun debugInRider(node: TestNode) {
        val path = node.dto.filePath ?: return
        val line = (node.dto.line ?: return) - 1
        val action = ActionManager.getInstance().getAction(RIDER_DEBUG_TEST_ACTION) ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, maxOf(0, line), 0), true) ?: return
        val methodName = node.dto.method?.substringBefore('(')
        editor.caretModel.moveToOffset(TestLocator.caretOffset(editor.document.charsSequence, line, methodName))

        service.scope.launch(Dispatchers.EDT) {
            repeat(DEBUG_ATTEMPTS) { attempt ->
                delay(DEBUG_RETRY_MS)
                val accepted = suspendCancellableCoroutine { cont ->
                    ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, "NoCrunchTreePopup", true)
                        .doWhenDone(Runnable { if (cont.isActive) cont.resumeWith(Result.success(true)) })
                        .doWhenRejected(Runnable { if (cont.isActive) cont.resumeWith(Result.success(false)) })
                }
                LOG.info("Debug Test ${node.dto.key}: attempt ${attempt + 1} ${if (accepted) "accepted" else "rejected"}")
                if (accepted) return@launch
            }
            HintManager.getInstance().showErrorHint(editor, "Rider couldn't find a test to debug here")
        }
    }

    private companion object {
        /** Rider's editor context action that debugs the test(s) at the caret. */
        const val RIDER_DEBUG_TEST_ACTION = "RiderUnitTestDebugContextAction"
        const val DEBUG_ATTEMPTS = 12
        const val DEBUG_RETRY_MS = 250L
        // Keeps the logger category the code had in NoCrunchPanel, so existing log settings and searches still match.
        val LOG = logger<NoCrunchPanel>()
    }
}
