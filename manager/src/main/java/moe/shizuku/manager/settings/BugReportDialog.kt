package moe.shizuku.manager.settings

import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.BugReportDialogBinding
import moe.shizuku.manager.ktx.asLink
import moe.shizuku.manager.ktx.applyTemplateArgs
import moe.shizuku.manager.ui.screen.readDiagnostics
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.shareAppLog
import moe.shizuku.manager.worker.AdbStartWorker

/**
 * How much of the app's own log travels with a report.
 *
 * It goes into the body of an issue, and that body is carried in a URL: the whole window is four
 * thousand lines of a day, which neither fits in one nor is what a report is read for. It is filed
 * right after the thing went wrong, so the end of the log is where the answer is, and the far end
 * of the day is the part with nothing to do with it. A long line is cut for the same reason, so
 * one dumpsys line cannot take the room a hundred ordinary ones share.
 */
private const val REPORT_LOG_LINES = 100
private const val REPORT_LOG_LINE_CHARS = 200

class BugReportDialog : DialogFragment() {

    private lateinit var binding: BugReportDialogBinding

    /**
     * The same block the home screen's Copy button produces, read once while the dialog is open
     * and put at the top of the body.
     *
     * A report that arrives without it costs three questions before it can be acted on - which
     * Android, which build, is the service even up - and the person filing it is the one least
     * able to answer them, because the app is the only thing that knows. Read here rather than
     * handed in: this dialog is reached from Settings, which keeps none of that state.
     *
     * Empty only in the moment between the dialog appearing and the read finishing, where the
     * body falls back to the plain text rather than blocking the button on a binder call.
     */
    @Volatile
    private var diagnostics: String = ""

    /**
     * The app's own log, read with the diagnostics and for the same reason: the block above says
     * what state the device was in, and this says what the app did about it. A report holding one
     * and not the other is the one that comes back asking for the other.
     */
    @Volatile
    private var log: List<String> = emptyList()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = BugReportDialogBinding.inflate(layoutInflater)

        // Releases and issues are this fork's; the wiki belongs to the fork this is based on,
        // because it documents the behaviour that came from there.
        val updateLink = getString(R.string.bug_report_dialog_link_update)
            .asLink("https://github.com/rushiranpise/Shizuku-Next/releases/latest")

        val wikiLink = getString(R.string.bug_report_dialog_link_wiki)
            .asLink("https://github.com/thedjchi/Shizuku/wiki#troubleshooting")

        val issuesLink = getString(R.string.bug_report_dialog_link_issues)
            .asLink("https://github.com/rushiranpise/Shizuku-Next/issues")

        binding.apply {
            updateText.applyTemplateArgs(updateLink)
            wikiText.applyTemplateArgs(wikiLink)
            issuesText.applyTemplateArgs(issuesLink)
            methodText.applyTemplateArgs("GitHub")
            shareLog.setOnClickListener { shareLog() }
        }

        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) {
                readDiagnostics(context) to Diag.tail(REPORT_LOG_LINES, REPORT_LOG_LINE_CHARS)
            }
            diagnostics = read.first
            log = read.second
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_report_bug)
            .setView(binding.root)
            .setPositiveButton("GitHub") { _, _ ->
                val url = "https://github.com/rushiranpise/Shizuku-Next/issues/new" +
                    "?body=" + Uri.encode(reportBody(context))
                CustomTabsHelper.launchUrlOrCopy(context, url)
            }
            .setNeutralButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
    }

    /**
     * What the issue starts with: the diagnostics block, then the prompt to describe the bug, then
     * the app's log.
     *
     * The block goes first because it is the part that is already known. The log goes last because
     * it is the longest and the least read, and what it is for is the report that says "the start
     * fails" and nothing more - the answer to that is usually a line in there. Neither is invented
     * when the read has not finished, so the body is never blocked on it.
     *
     * The log is fenced so it reads as lines rather than as prose and labelled with how much of it
     * is there, and it is a tail of the window rather than all of it because of where this body
     * goes. The rest is one tap away on the button under this text, which hands the whole file to
     * wherever the reporter wants it; the tail is in the body as well because a report is often
     * filed before anybody thinks to attach anything.
     */
    private fun reportBody(context: Context): String = buildString {
        if (diagnostics.isNotEmpty()) {
            appendLine(diagnostics)
            appendLine()
            appendLine("---")
            appendLine()
        }
        append(context.getString(R.string.bug_report_dialog_body_prompt))
        if (log.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(context.getString(R.string.bug_report_dialog_log_note, log.size))
            appendLine()
            appendLine("```")
            log.forEach { entry -> appendLine(entry) }
            appendLine("```")
        }
    }

    /**
     * The whole log, handed to whatever the reporter picks so they can attach it.
     *
     * [shareAppLog] does the writing and the sharing, and the same call is behind the Log screen's
     * own Share - one implementation, so what a report attaches and what the screen sends cannot
     * be two different things. What this adds is only what to say when it cannot: a button that
     * appears to do nothing is worse than one that reports it could not.
     */
    private fun shareLog() {
        lifecycleScope.launch {
            val context = context ?: return@launch
            if (!shareAppLog(context)) {
                Toast.makeText(context, R.string.log_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity is BugReportDialogActivity) activity?.finish()
    }

}
