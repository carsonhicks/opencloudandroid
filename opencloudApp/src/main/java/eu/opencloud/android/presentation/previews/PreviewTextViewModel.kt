/**
 * openCloud Android client application
 *
 * @author Juan Carlos Garrote Gascón
 * @author Parneet Singh
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

package eu.opencloud.android.presentation.previews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.opencloud.android.R
import eu.opencloud.android.domain.files.model.FileMenuOption
import eu.opencloud.android.domain.files.model.OCFile
import eu.opencloud.android.domain.files.usecases.GetFileByIdAsStreamUseCase
import eu.opencloud.android.domain.files.usecases.SaveConflictUseCase
import eu.opencloud.android.providers.ContextProvider
import eu.opencloud.android.providers.CoroutinesDispatcherProvider
import eu.opencloud.android.usecases.files.FilterFileMenuOptionsUseCase
import eu.opencloud.android.usecases.transfers.uploads.UploadFileInConflictUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class PreviewTextViewModel(
    private val filterFileMenuOptionsUseCase: FilterFileMenuOptionsUseCase,
    getFileByIdAsStreamUseCase: GetFileByIdAsStreamUseCase,
    private val saveConflictUseCase: SaveConflictUseCase,
    private val uploadFileInConflictUseCase: UploadFileInConflictUseCase,
    private val contextProvider: ContextProvider,
    private val coroutinesDispatcherProvider: CoroutinesDispatcherProvider,
    ocFile: OCFile,
) : ViewModel() {

    private val _menuOptions: MutableStateFlow<List<FileMenuOption>> = MutableStateFlow(emptyList())
    val menuOptions: StateFlow<List<FileMenuOption>> = _menuOptions

    private val currentFile: Flow<OCFile?> = getFileByIdAsStreamUseCase(GetFileByIdAsStreamUseCase.Params(ocFile.id!!))

    fun getCurrentFile(): Flow<OCFile?> = currentFile

    fun filterMenuOptions(file: OCFile, accountName: String) {
        val shareViaLinkAllowed = contextProvider.getBoolean(R.bool.share_via_link_feature)
        val shareWithUsersAllowed = contextProvider.getBoolean(R.bool.share_with_users_feature)
        val sendAllowed = contextProvider.getString(R.string.send_files_to_other_apps).equals("on", ignoreCase = true)
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            val result = filterFileMenuOptionsUseCase(
                FilterFileMenuOptionsUseCase.Params(
                    files = listOf(file),
                    accountName = accountName,
                    isAnyFileVideoPreviewing = false,
                    displaySelectAll = false,
                    displaySelectInverse = false,
                    onlyAvailableOfflineFiles = false,
                    onlySharedByLinkFiles = false,
                    shareViaLinkAllowed = shareViaLinkAllowed,
                    shareWithUsersAllowed = shareWithUsersAllowed,
                    sendAllowed = sendAllowed,
                )
            )
            result.apply {
                remove(FileMenuOption.RENAME)
                remove(FileMenuOption.MOVE)
                remove(FileMenuOption.COPY)
                remove(FileMenuOption.SYNC)
            }
            _menuOptions.update { result }
        }
    }

    private val _saveResult = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    /** Emits true when the edited text was written locally and the upload was enqueued, false otherwise. */
    val saveResult: SharedFlow<Boolean> = _saveResult

    /**
     * Writes [text] to the local copy of [file] and enqueues an upload that only succeeds if the file
     * has not changed in the server since the version the user started editing.
     */
    fun saveEditedText(file: OCFile, text: String) {
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            val success = try {
                val localPath = file.storagePath ?: error("File is not available locally")
                val localFile = File(localPath)
                val original = localFile.readText()
                // The preview shows text with \n separators and no trailing newline; restore the original style.
                var content = text
                if (original.contains("\r\n")) content = content.replace("\n", "\r\n")
                if (original.endsWith("\n") && !content.endsWith("\n")) {
                    content += if (original.contains("\r\n")) "\r\n" else "\n"
                }
                localFile.writeText(content)

                // Make the upload send If-Match with the etag we based our edit on, so a newer server version
                // is reported as a conflict instead of being silently overwritten.
                val fileId = file.id
                val baseEtag = file.etag
                if (fileId != null && !baseEtag.isNullOrEmpty()) {
                    saveConflictUseCase(SaveConflictUseCase.Params(fileId, baseEtag))
                }
                uploadFileInConflictUseCase(
                    UploadFileInConflictUseCase.Params(
                        accountName = file.owner,
                        localPath = localPath,
                        uploadFolderPath = file.getParentRemotePath(),
                        spaceId = file.spaceId,
                    )
                )
                true
            } catch (e: Exception) {
                Timber.e(e, "Error saving edited text")
                false
            }
            _saveResult.emit(success)
        }
    }

    companion object {
        const val NO_POSITION: Int = -1
        const val TAB_MARKDOWN_POSITION: Int = 0
        const val TAB_TEXT_POSITION: Int = 1
    }
}
