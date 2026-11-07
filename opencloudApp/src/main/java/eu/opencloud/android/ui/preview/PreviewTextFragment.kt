/**
 * openCloud Android client application
 *
 * @author Christian Schabesberger
 * @author Shashvat Kedia
 * @author Juan Carlos Garrote Gascón
 * @author Parneet Singh
 * @author Aitor Ballesteros Pavón
 * @author Jorge Aguado Recio
 *
 * Copyright (C) 2024 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package eu.opencloud.android.ui.preview

import android.accounts.Account
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import eu.opencloud.android.R
import eu.opencloud.android.databinding.PreviewTextFragmentBinding
import eu.opencloud.android.domain.files.model.OCFile
import eu.opencloud.android.extensions.collectLatestLifecycleFlow
import eu.opencloud.android.extensions.filterMenuOptions
import eu.opencloud.android.extensions.sendDownloadedFilesByShareSheet
import eu.opencloud.android.presentation.files.operations.FileOperation
import eu.opencloud.android.presentation.files.operations.FileOperationsViewModel
import eu.opencloud.android.presentation.files.removefile.RemoveFilesDialogFragment
import eu.opencloud.android.presentation.files.removefile.RemoveFilesDialogFragment.Companion.TAG_REMOVE_FILES_DIALOG_FRAGMENT
import eu.opencloud.android.presentation.previews.PreviewTextViewModel
import eu.opencloud.android.ui.dialog.LoadingDialog
import eu.opencloud.android.ui.fragment.FileFragment
import eu.opencloud.android.utils.PreferenceUtils
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.component.getScopeName
import org.koin.core.parameter.parametersOf
import timber.log.Timber
import java.io.BufferedWriter
import java.io.FileInputStream
import java.io.IOException
import java.io.StringWriter
import java.util.Scanner

class PreviewTextFragment : FileFragment() {
    private var account: Account? = null
    private lateinit var textLoadTask: TextLoadAsyncTask

    private val previewTextViewModel by viewModel<PreviewTextViewModel> {
        parametersOf(requireArguments().getParcelable(EXTRA_FILE))
    }
    private val fileOperationsViewModel by viewModel<FileOperationsViewModel>()

    private lateinit var binding: PreviewTextFragmentBinding

    // Text as loaded from the local file (null until loaded), and edit-mode state
    private var loadedText: String? = null
    private var isEditing = false
    private var isDirty = false
    private var hiddenPreviewViews: List<View> = emptyList()

    private val unsavedChangesBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showDiscardChangesDialog { stopEditing() ; finish() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)
        Timber.v("onCreateView")

        binding = PreviewTextFragmentBinding.inflate(inflater, container, false)
        return binding.root.apply {
            filterTouchesWhenObscured = PreferenceUtils.shouldDisallowTouchesWithOtherVisibleWindows(context)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file: OCFile?
        if (savedInstanceState == null) {
            val args = requireArguments()
            file = args.getParcelable(EXTRA_FILE)
            account = args.getParcelable(EXTRA_ACCOUNT)
            if (file == null) {
                throw IllegalStateException("Instanced with a NULL OCFile")
            }

            if (account == null) {
                throw IllegalStateException("Instanced with a NULL openCloud Account")
            }
        } else {
            file = savedInstanceState.getParcelable(EXTRA_FILE)
            account = savedInstanceState.getParcelable(EXTRA_ACCOUNT)
        }
        requireActivity().title = getString(R.string.text_preview_label)
        setFile(file)
        setHasOptionsMenu(true)
        isOpen = true
        currentFilePreviewing = file
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        collectLatestLifecycleFlow(previewTextViewModel.getCurrentFile()) { currentFile ->
            if (currentFile != null) {
                file = currentFile
                requireActivity().invalidateOptionsMenu()
            } else {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }

        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, unsavedChangesBackCallback)
        binding.textEditor.doAfterTextChanged {
            if (isEditing && !isDirty) {
                isDirty = true
                unsavedChangesBackCallback.isEnabled = true
            }
        }
        collectLatestLifecycleFlow(previewTextViewModel.saveResult) { success ->
            if (success) {
                stopEditing()
                Snackbar.make(requireView(), R.string.text_editor_saved, Snackbar.LENGTH_LONG).show()
                loadAndShowTextPreview()
            } else {
                binding.textEditor.isEnabled = true
                Snackbar.make(requireView(), R.string.text_editor_save_failed, Snackbar.LENGTH_LONG).show()
            }
        }

        loadAndShowTextPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.apply {
            putParcelable(EXTRA_FILE, file)
            putParcelable(EXTRA_ACCOUNT, account)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_edit_text -> {
                startEditing()
                true
            }

            R.id.action_save_text -> {
                binding.textEditor.isEnabled = false
                previewTextViewModel.saveEditedText(file, binding.textEditor.text.toString())
                true
            }

            R.id.action_discard_text -> {
                if (isDirty) showDiscardChangesDialog { stopEditing() } else stopEditing()
                true
            }

            R.id.action_share_file -> {
                mContainerActivity.fileOperationsHelper.showShareFile(file)
                true
            }

            R.id.action_open_file_with -> {
                openFile()
                true
            }

            R.id.action_remove_file -> {
                RemoveFilesDialogFragment.newInstance(file).show(requireFragmentManager(), TAG_REMOVE_FILES_DIALOG_FRAGMENT)
                true
            }

            R.id.action_see_details -> {
                seeDetails()
                true
            }

            R.id.action_send_file -> {
                requireActivity().sendDownloadedFilesByShareSheet(listOf(file))
                true
            }

            R.id.action_sync_file -> {
                account?.let { fileOperationsViewModel.performOperation(FileOperation.SynchronizeFileOperation(file, it.name)) }
                true
            }

            R.id.action_set_available_offline -> {
                val fileToSetAsAvailableOffline = ArrayList<OCFile>()
                fileToSetAsAvailableOffline.add(file)
                fileOperationsViewModel.performOperation(FileOperation.SetFilesAsAvailableOffline(fileToSetAsAvailableOffline))
                Snackbar.make(requireView(), R.string.confirmation_set_available_offline, Snackbar.LENGTH_LONG).show()
                true
            }

            R.id.action_unset_available_offline -> {
                val fileToUnsetAsAvailableOffline = ArrayList<OCFile>()
                fileToUnsetAsAvailableOffline.add(file)
                fileOperationsViewModel.performOperation(FileOperation.UnsetFilesAsAvailableOffline(fileToUnsetAsAvailableOffline))
                Snackbar.make(requireView(), R.string.confirmation_unset_available_offline, Snackbar.LENGTH_LONG).show()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }

    override fun onDestroyView() {
        super.onDestroyView()
        textLoadTask.apply {
            cancel(true)
            dismissLoadingDialog()
        }
        isOpen = false
        currentFilePreviewing = null
    }

    override fun onFileMetadataChanged(updatedFile: OCFile?) {
        updatedFile?.let {
            file = updatedFile
        }
        requireActivity().invalidateOptionsMenu()
    }

    override fun onFileMetadataChanged() {
        mContainerActivity.storageManager?.let {
            file = it.getFileByPath(file.remotePath)
        }
        requireActivity().invalidateOptionsMenu()
    }

    override fun onFileContentChanged() {
        // Do not replace what the user is typing; the preview is refreshed when leaving edit mode
        if (isEditing) return
        loadAndShowTextPreview()
    }

    override fun updateViewForSyncInProgress() {
        // Nothing to do here, sync is not shown in previews
    }

    override fun updateViewForSyncOff() {
        // Nothing to do here, sync is not shown in previews
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        mContainerActivity.storageManager?.let {
            val safeFile = file
            val accountName = it.account.name
            previewTextViewModel.filterMenuOptions(safeFile, accountName)

            collectLatestLifecycleFlow(previewTextViewModel.menuOptions) { menuOptions ->
                val hasWritePermission = safeFile.hasWritePermission
                menu.filterMenuOptions(menuOptions, hasWritePermission)
                applyEditMenuState(menu)
            }
        }

        menu.findItem(R.id.action_search)?.apply {
            isVisible = false
            isEnabled = false
        }

        applyEditMenuState(menu)
        setRolesAccessibilityToMenuItems(menu)
    }

    /** Shows Edit when the file can be edited; in edit mode shows only Save and Discard. */
    private fun applyEditMenuState(menu: Menu) {
        val canEdit = !isEditing && loadedText != null && canBeEdited(file)
        menu.findItem(R.id.action_edit_text)?.apply { isVisible = canEdit; isEnabled = canEdit }
        menu.findItem(R.id.action_save_text)?.apply { isVisible = isEditing; isEnabled = isEditing }
        menu.findItem(R.id.action_discard_text)?.apply { isVisible = isEditing; isEnabled = isEditing }
        if (isEditing) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.itemId != R.id.action_save_text && item.itemId != R.id.action_discard_text) {
                    item.isVisible = false
                    item.isEnabled = false
                }
            }
        }
    }

    private fun startEditing() {
        val text = loadedText ?: return
        isEditing = true
        isDirty = false
        hiddenPreviewViews = listOf(binding.textLayout.root, binding.tabLayout, binding.viewPager)
            .filter { it.parent != null && it.visibility == View.VISIBLE }
        hiddenPreviewViews.forEach { it.visibility = View.GONE }
        binding.textEditor.apply {
            setText(text)
            isEnabled = true
            visibility = View.VISIBLE
            setSelection(text.length)
            requestFocus()
        }
        (requireContext().getSystemService(InputMethodManager::class.java))
            ?.showSoftInput(binding.textEditor, InputMethodManager.SHOW_IMPLICIT)
        requireActivity().invalidateOptionsMenu()
    }

    private fun stopEditing() {
        isEditing = false
        isDirty = false
        unsavedChangesBackCallback.isEnabled = false
        (requireContext().getSystemService(InputMethodManager::class.java))
            ?.hideSoftInputFromWindow(binding.textEditor.windowToken, 0)
        binding.textEditor.visibility = View.GONE
        hiddenPreviewViews.forEach { it.visibility = View.VISIBLE }
        hiddenPreviewViews = emptyList()
        requireActivity().invalidateOptionsMenu()
    }

    private fun showDiscardChangesDialog(onDiscard: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_editor_unsaved_title)
            .setMessage(R.string.text_editor_unsaved_message)
            .setPositiveButton(R.string.text_editor_discard) { _, _ -> onDiscard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setRolesAccessibilityToMenuItems(menu: Menu) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            menu.findItem(R.id.action_see_details)?.contentDescription =
                "${getString(R.string.actionbar_see_details)} ${getString(R.string.button_role_accessibility)}"
        }
    }

    private fun loadAndShowTextPreview() {
        textLoadTask = TextLoadAsyncTask(
            binding.textLayout.textPreview,
            binding.top,
            binding.textLayout.root,
            binding.tabLayout,
            binding.viewPager
        )
        textLoadTask.execute(file)
    }

    private fun openFile() {
        mContainerActivity.fileOperationsHelper.openFile(file)
        finish()
    }

    private fun seeDetails() {
        mContainerActivity.showDetails(file)
    }

    private fun finish() {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    private inner class TextLoadAsyncTask(
        var textViewReference: TextView,
        var rootView: RelativeLayout,
        var textLayout: View,
        var tabLayout: TabLayout,
        var viewPager: ViewPager2
    ) : AsyncTask<OCFile, Unit, StringWriter>() {

        private val dialogWaitTag = "DIALOG_WAIT"
        private lateinit var mimeType: String

        override fun onPreExecute() {
            showLoadingDialog()
        }

        override fun doInBackground(vararg params: OCFile?): StringWriter {
            if (params.size != 1) {
                throw IllegalArgumentException("The parameter to ${this.getScopeName()} must be (1) the file location")
            }

            val file = params[0] as OCFile
            val location = file.storagePath
            mimeType = file.mimeType

            var inputStream: FileInputStream? = null
            var scanner: Scanner? = null
            val source = StringWriter()
            val bufferedWriter = BufferedWriter(source)

            try {
                inputStream = FileInputStream(location)
                scanner = Scanner(inputStream)
                while (scanner.hasNextLine()) {
                    bufferedWriter.append(scanner.nextLine())
                    if (scanner.hasNextLine()) {
                        bufferedWriter.append("\n")
                    }
                }
                bufferedWriter.close()
                val exc = scanner.ioException()
                if (exc != null) {
                    throw exc
                }
            } catch (e: IOException) {
                Timber.e(e)
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close()
                    } catch (e: IOException) {
                        Timber.e(e)
                    }
                }
                scanner?.close()
            }
            return source
        }

        override fun onPostExecute(result: StringWriter) {
            val text = String(result.buffer)
            loadedText = text
            activity?.invalidateOptionsMenu()
            showPreviewText(text, mimeType, rootView, textViewReference, textLayout, tabLayout, viewPager)

            try {
                dismissLoadingDialog()
            } catch (illegalStateException: java.lang.IllegalStateException) {
                Timber.w(illegalStateException, "Dismissing dialog not allowed after onSaveInstanceState")
            }
        }

        fun showLoadingDialog() {
            val waitDialogFragment = requireActivity().supportFragmentManager.findFragmentByTag(dialogWaitTag)
            val loading: LoadingDialog

            if (waitDialogFragment == null) {
                loading = LoadingDialog.newInstance(R.string.wait_a_moment, false)
                val fragmentManager = requireActivity().supportFragmentManager
                val fragmentTransaction = fragmentManager.beginTransaction()
                loading.show(fragmentTransaction, dialogWaitTag)
            } else {
                loading = waitDialogFragment as LoadingDialog
                loading.showsDialog = true
            }
        }

        fun dismissLoadingDialog() {
            val waitDialogFragment = requireActivity().supportFragmentManager.findFragmentByTag(dialogWaitTag)
            waitDialogFragment?.let {
                val loading = waitDialogFragment as LoadingDialog
                loading.dismiss()
            }
        }

        private fun showPreviewText(
            text: String,
            mimeType: String,
            rootView: RelativeLayout,
            textView: TextView,
            textLayout: View,
            tabLayout: TabLayout,
            viewPager: ViewPager2
        ) {
            if (mimeType == "text/markdown") {
                rootView.removeView(textLayout)
                showFormatType(text, mimeType, tabLayout, viewPager)
                tabLayout.visibility = View.VISIBLE
                viewPager.visibility = View.VISIBLE
            } else {
                rootView.removeView(tabLayout)
                rootView.removeView(viewPager)
                textView.text = text
                textLayout.visibility = View.VISIBLE
            }
        }

        private fun showFormatType(
            text: String,
            mimeType: String,
            tabLayout: TabLayout,
            viewPager: ViewPager2
        ) {
            val adapter = PreviewFormatTextFragmentStateAdapter(this@PreviewTextFragment, text, mimeType)
            viewPager.adapter = adapter

            TabLayoutMediator(tabLayout, viewPager) { tab: TabLayout.Tab, position: Int ->
                if (position == 0) {
                    tab.text = adapter.formatTypes[mimeType]
                } else {
                    tab.text = adapter.formatTypes[PreviewFormatTextFragmentStateAdapter.TYPE_PLAIN]
                }
            }.attach()
        }
    }


    companion object {
        private const val EXTRA_FILE = "FILE"
        private const val EXTRA_ACCOUNT = "ACCOUNT"
        var isOpen = false
        var currentFilePreviewing: OCFile? = null

        fun newInstance(file: OCFile, account: Account): PreviewTextFragment {
            val args = Bundle().apply {
                putParcelable(EXTRA_FILE, file)
                putParcelable(EXTRA_ACCOUNT, account)
            }

            return PreviewTextFragment().apply {
                arguments = args
            }
        }

        private const val MAX_EDITABLE_SIZE_BYTES = 1_000_000L

        fun canBeEdited(file: OCFile?): Boolean =
            file != null && file.hasWritePermission && file.isAvailableLocally &&
                    file.storagePath != null && file.length <= MAX_EDITABLE_SIZE_BYTES &&
                    (file.mimeType == "text/plain" || file.mimeType == "text/markdown")

        fun canBePreviewed(file: OCFile?): Boolean {
            val unsupportedTypes = listOf(
                "test/richtest",
                "text/rtf",
                "text/vnd.abc",
                "text/vnd.fmi.flexstor",
                "text/vnd.rn-realtext",
                "text/vnd.wap.wml",
                "text/vnd.wap.wmlscript",
                "text/uri-list",
            )

            return (file != null && file.isAvailableLocally && file.isText &&
                    !unsupportedTypes.contains(file.mimeType) &&
                    !unsupportedTypes.contains(file.getMimeTypeFromName()))
        }
    }
}

