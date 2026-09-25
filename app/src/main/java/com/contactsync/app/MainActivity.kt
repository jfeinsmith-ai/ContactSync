package com.contactsync.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.contactsync.app.data.AndroidContactStore
import com.contactsync.app.domain.AccountRef
import com.contactsync.app.domain.ClashDecision
import com.contactsync.app.domain.ContactRecord
import com.contactsync.app.domain.CopyEngine
import com.contactsync.app.domain.CopyOutcome
import com.contactsync.app.domain.NameClash
import com.contactsync.app.domain.Normalizers
import com.contactsync.app.domain.PreparedRun
import com.contactsync.app.domain.StalePlanException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var store: AndroidContactStore
    private lateinit var engine: CopyEngine
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    private lateinit var fromSpinner: Spinner
    private lateinit var toSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var counts: TextView
    private lateinit var reviewHeading: TextView
    private lateinit var reviewList: ListView
    private lateinit var skipAllButton: Button
    private lateinit var copyButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var cancelButton: Button
    private var accounts: List<AccountRef> = emptyList()
    private var run: PreparedRun? = null
    private var clashAdapter: ClashAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidContactStore(this)
        engine = CopyEngine(store)
        setContentView(buildUi())
        scanButton.setOnClickListener { startScan() }
        skipAllButton.setOnClickListener {
            run?.plan?.nameClashes?.forEach { it.decision = ClashDecision.SKIP }
            clashAdapter?.notifyDataSetChanged()
            updateCopyButton()
        }
        copyButton.setOnClickListener { confirmCopy() }
        cancelButton.setOnClickListener {
            cancelled.set(true)
            cancelButton.isEnabled = false
            status.text = "Cancelling after the current contact…"
        }
        ensurePermissionsAndLoadAccounts()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CONTACT_PERMISSION_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadAccounts()
        } else {
            status.text = "ContactSync needs read and write contacts permission to scan and copy contacts."
        }
    }

    private fun ensurePermissionsAndLoadAccounts() {
        val missing = REQUIRED_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) loadAccounts()
        else requestPermissions(missing.toTypedArray(), CONTACT_PERMISSION_REQUEST)
    }

    private fun loadAccounts() {
        try {
            accounts = store.googleAccounts()
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, accounts).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            fromSpinner.adapter = adapter
            toSpinner.adapter = adapter
            if (accounts.size > 1) toSpinner.setSelection(1)
            scanButton.isEnabled = accounts.size >= 2
            status.text = if (accounts.size >= 2) {
                "Choose two different Google accounts, then Scan. Scanning never writes contacts."
            } else {
                "ContactSync needs two Google accounts available on this device."
            }
        } catch (_: SecurityException) {
            status.text = "Android did not allow access to the device's Google accounts."
        }
    }

    private fun startScan() {
        val from = fromSpinner.selectedItem as? AccountRef ?: return
        val to = toSpinner.selectedItem as? AccountRef ?: return
        if (from == to) {
            status.text = "FROM and TO must be different Google accounts."
            return
        }
        setBusy(true)
        status.text = "Scanning ${from.name} and ${to.name}…"
        counts.text = ""
        clearReview()
        worker.execute {
            val result = runCatching { engine.scan(from, to) }
            main.post {
                result.onSuccess {
                    run = it
                    renderPlan(it)
                    status.text = "Scan complete. No contacts were changed."
                }.onFailure {
                    run = null
                    status.text = "Scan failed: ${it.userMessage()}"
                }
                setBusy(false)
            }
        }
    }

    private fun renderPlan(prepared: PreparedRun) {
        val plan = prepared.plan
        counts.text = buildString {
            appendLine("Source contacts scanned: ${plan.sourceScanned}")
            appendLine("New contacts ready to copy: ${plan.ready.size}")
            appendLine("Existing contacts skipped by email: ${plan.emailSkipped.size}")
            appendLine("Existing contacts skipped by LinkedIn URL: ${plan.linkedInSkipped.size}")
            appendLine("Name-only clashes requiring review: ${plan.nameClashes.size}")
            appendLine("Ambiguous identifier collisions skipped: ${plan.ambiguousSkipped.size}")
            appendLine("Records without usable identity skipped: ${plan.unusableSkipped.size}")
            append("Read errors: ${plan.readErrors}")
        }
        val hasClashes = plan.nameClashes.isNotEmpty()
        reviewHeading.visibility = if (hasClashes) View.VISIBLE else View.GONE
        reviewList.visibility = if (hasClashes) View.VISIBLE else View.GONE
        skipAllButton.visibility = if (hasClashes) View.VISIBLE else View.GONE
        clashAdapter = ClashAdapter(plan.nameClashes) { updateCopyButton() }
        reviewList.adapter = clashAdapter
        reviewList.layoutParams = reviewList.layoutParams.apply {
            height = dp((plan.nameClashes.size * 220).coerceIn(0, 520))
        }
        copyButton.visibility = View.VISIBLE
        updateCopyButton()
    }

    private fun clearReview() {
        reviewHeading.visibility = View.GONE
        reviewList.visibility = View.GONE
        skipAllButton.visibility = View.GONE
        copyButton.visibility = View.GONE
        clashAdapter = null
    }

    private fun updateCopyButton() {
        val n = run?.plan?.selectedForCopy?.size ?: 0
        copyButton.text = "Copy $n contacts"
        copyButton.isEnabled = n > 0
    }

    private fun confirmCopy() {
        val prepared = run ?: return
        val n = prepared.plan.selectedForCopy.size
        AlertDialog.Builder(this)
            .setTitle("Copy $n contacts?")
            .setMessage(
                "Copy $n contacts from\n${prepared.from.name}\n\nto\n${prepared.to.name}\n\n" +
                    "Existing destination contacts will not be updated or merged.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Copy $n") { _, _ -> startCopy(prepared) }
            .show()
    }

    private fun startCopy(prepared: PreparedRun) {
        val from = fromSpinner.selectedItem as? AccountRef ?: return
        val to = toSpinner.selectedItem as? AccountRef ?: return
        cancelled.set(false)
        setBusy(true)
        copyButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.max = prepared.plan.selectedForCopy.size
        progress.progress = 0
        cancelButton.visibility = View.VISIBLE
        cancelButton.isEnabled = true
        status.text = "Copying contacts to ${prepared.to.name}…"
        worker.execute {
            val result = runCatching {
                engine.copy(prepared, from, to, cancelled::get) { completed, total ->
                    main.post {
                        progress.max = total
                        progress.progress = completed
                        status.text = "Copied or checked $completed of $total contacts…"
                    }
                }
            }
            main.post {
                result.onSuccess { showOutcome(it, prepared.to) }
                    .onFailure {
                        status.text = when (it) {
                            is StalePlanException -> it.message.orEmpty()
                            else -> "Copy stopped: ${it.userMessage()}"
                        }
                    }
                setBusy(false)
                progress.visibility = View.GONE
                cancelButton.visibility = View.GONE
                run = null
                clearReview()
            }
        }
    }

    private fun showOutcome(outcome: CopyOutcome, to: AccountRef) {
        status.text = buildString {
            appendLine(if (outcome.cancelled) "Copy cancelled between contacts." else "Copy finished.")
            appendLine("Created on device: ${outcome.created}")
            appendLine("Already present when rechecked: ${outcome.alreadyPresent}")
            appendLine("Failed: ${outcome.failed}")
            outcome.fatalMessage?.let { appendLine("Stopped: $it") }
            append("Verify Google sync manually: open contacts.google.com for ${to.name} and confirm the new contacts appear.")
        }
    }

    private fun setBusy(busy: Boolean) {
        fromSpinner.isEnabled = !busy
        toSpinner.isEnabled = !busy
        scanButton.isEnabled = !busy && accounts.size >= 2
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        content.addView(TextView(this).apply {
            text = "ContactSync"
            textSize = 30f
            setTextColor(Color.rgb(18, 63, 48))
        })
        content.addView(TextView(this).apply {
            text = "One-way, on-demand contact copying. Nothing is deleted, merged, or updated."
            textSize = 16f
            setPadding(0, dp(6), 0, dp(22))
        })
        content.addView(label("FROM Google account"))
        fromSpinner = Spinner(this)
        content.addView(fromSpinner, matchWrap())
        content.addView(label("TO Google account").apply { setPadding(0, dp(18), 0, dp(6)) })
        toSpinner = Spinner(this)
        content.addView(toSpinner, matchWrap())
        scanButton = Button(this).apply {
            text = "Scan"
            isEnabled = false
        }
        content.addView(scanButton, matchWrap(top = 20))
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, dp(12))
        }
        content.addView(status, matchWrap())
        counts = TextView(this).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
        }
        content.addView(counts, matchWrap())
        reviewHeading = TextView(this).apply {
            text = "Review name-only clashes"
            textSize = 21f
            setTextColor(Color.rgb(18, 63, 48))
            setPadding(0, dp(24), 0, dp(8))
            visibility = View.GONE
        }
        content.addView(reviewHeading, matchWrap())
        skipAllButton = Button(this).apply {
            text = "Skip all remaining name clashes"
            visibility = View.GONE
        }
        content.addView(skipAllButton, matchWrap())
        reviewList = ListView(this).apply {
            dividerHeight = dp(12)
            visibility = View.GONE
        }
        content.addView(reviewList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        copyButton = Button(this).apply { visibility = View.GONE }
        content.addView(copyButton, matchWrap(top = 18))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        content.addView(progress, matchWrap(top = 18))
        cancelButton = Button(this).apply {
            text = "Cancel after current contact"
            visibility = View.GONE
        }
        content.addView(cancelButton, matchWrap(top = 10))
        return ScrollView(this).apply { addView(content) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.DKGRAY)
        setPadding(0, 0, 0, dp(6))
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Throwable.userMessage(): String = message?.take(240) ?: javaClass.simpleName

    private inner class ClashAdapter(
        private val clashes: List<NameClash>,
        private val changed: () -> Unit,
    ) : BaseAdapter() {
        override fun getCount(): Int = clashes.size
        override fun getItem(position: Int): NameClash = clashes[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
            val clash = getItem(position)
            val row = (recycled as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.rgb(244, 248, 245))
            }
            row.removeAllViews()
            row.addView(TextView(this@MainActivity).apply {
                text = "FROM: ${details(clash.source)}\n\nTO match: ${clash.matches.joinToString("\n") { details(it) }}"
                textSize = 14f
            })
            val choices = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.START
            }
            val skip = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "Skip"
            }
            val create = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "Create separate"
            }
            choices.addView(skip)
            choices.addView(create)
            choices.check(if (clash.decision == ClashDecision.SKIP) skip.id else create.id)
            choices.setOnCheckedChangeListener { _, checkedId ->
                clash.decision = if (checkedId == create.id) ClashDecision.CREATE_SEPARATE else ClashDecision.SKIP
                changed()
            }
            row.addView(choices)
            return row
        }

        private fun details(contact: ContactRecord): String {
            val email = contact.emails.firstOrNull()?.value
            val linkedIn = contact.websites.firstNotNullOfOrNull {
                Normalizers.linkedInPersonUrl(it.value)?.let { _ -> it.value }
            }
            val phones = contact.phones.joinToString { it.value }
            return listOfNotNull(
                contact.displayName,
                email?.let { "Email: $it" },
                linkedIn?.let { "LinkedIn: $it" },
                phones.takeIf { it.isNotBlank() }?.let { "Phone: $it" },
            ).joinToString(" • ")
        }
    }

    companion object {
        private const val CONTACT_PERMISSION_REQUEST = 42
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
    }
}

