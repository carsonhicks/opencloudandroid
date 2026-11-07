package eu.opencloud.android.presentation.viewmodels.previews

import eu.opencloud.android.domain.files.usecases.GetFileByIdAsStreamUseCase
import eu.opencloud.android.domain.files.usecases.SaveConflictUseCase
import eu.opencloud.android.presentation.previews.PreviewTextViewModel
import eu.opencloud.android.presentation.viewmodels.ViewModelTest
import eu.opencloud.android.providers.ContextProvider
import eu.opencloud.android.testutil.OC_FILE
import eu.opencloud.android.usecases.files.FilterFileMenuOptionsUseCase
import eu.opencloud.android.usecases.transfers.uploads.UploadFileInConflictUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@ExperimentalCoroutinesApi
class PreviewTextViewModelTest : ViewModelTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val saveConflictUseCase = mockk<SaveConflictUseCase>(relaxed = true)
    private val uploadUseCase = mockk<UploadFileInConflictUseCase>(relaxed = true)
    private lateinit var viewModel: PreviewTextViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testCoroutineDispatcher)
        val getFileStream = mockk<GetFileByIdAsStreamUseCase>()
        every { getFileStream(any()) } returns emptyFlow()
        viewModel = PreviewTextViewModel(
            mockk<FilterFileMenuOptionsUseCase>(),
            getFileStream,
            saveConflictUseCase,
            uploadUseCase,
            mockk<ContextProvider>(),
            coroutineDispatcherProvider,
            OC_FILE,
        )
    }

    @Test
    fun `save writes file keeping crlf and trailing newline then marks etag and uploads`() = runTest(testCoroutineDispatcher) {
        val local = tempFolder.newFile("note.txt").apply { writeText("a\r\nb\r\n") }
        val file = OC_FILE.copy(storagePath = local.absolutePath, etag = "etag-1")

        viewModel.saveEditedText(file, "a\nc")
        testCoroutineDispatcher.scheduler.advanceUntilIdle()

        
        assertEquals("a\r\nc\r\n", local.readText())
        verifyOrder {
            saveConflictUseCase(SaveConflictUseCase.Params(file.id!!, "etag-1"))
            uploadUseCase(match { it.localPath == local.absolutePath && it.accountName == file.owner })
        }
    }
}
