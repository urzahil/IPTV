package rocks.urzahil.iptv.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rocks.urzahil.iptv.sync.SyncScheduler
import rocks.urzahil.iptv.sync.workers.EpgSyncWorker
import rocks.urzahil.iptv.sync.workers.PlaylistSyncWorker
import rocks.urzahil.iptv.sync.workers.PruneWorker
import rocks.urzahil.iptv.sync.workers.SyncWorker

val failureRecorded = AtomicInteger()

/** Throws whatever the test sets; stands in for a sync that dies on hostile input. */
class ThrowingWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun sync(): Result = throw when (inputData.getString("kind")) {
        "oom" -> OutOfMemoryError("simulated")
        "stack" -> StackOverflowError()
        "cancel" -> CancellationException("cancelled")
        else -> IllegalStateException("http://host/live/user/pass/1.ts")
    }

    override suspend fun onUnexpectedFailure() { failureRecorded.incrementAndGet() }
}

class OkWorker(context: Context, params: WorkerParameters) : SyncWorker(context, params) {
    override suspend fun sync(): Result = Result.success()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncWorkerFailureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private inline fun <reified W : androidx.work.ListenableWorker> execute(vararg input: Pair<String, Any?>): Result {
        val worker = TestListenableWorkerBuilder<W>(context).setInputData(workDataOf(*input)).build()
        return runBlocking { (worker as androidx.work.CoroutineWorker).doWork() }
    }

    @Test fun outOfMemoryEndsTheRunWithFailureInsteadOfCrashing() {
        failureRecorded.set(0)
        assertThat(execute<ThrowingWorker>("kind" to "oom")).isEqualTo(Result.failure())
        assertThat(execute<ThrowingWorker>("kind" to "stack")).isEqualTo(Result.failure())
        assertThat(failureRecorded.get()).isEqualTo(2)
    }

    @Test fun anyOtherThrowableIsAFailureToo() {
        assertThat(execute<ThrowingWorker>("kind" to "ise")).isEqualTo(Result.failure())
    }

    @Test fun cancellationStillPropagates() {
        assertThrows(CancellationException::class.java) { execute<ThrowingWorker>("kind" to "cancel") }
    }

    @Test fun normalResultsAreUnchanged() {
        assertThat(execute<OkWorker>()).isEqualTo(Result.success())
    }

    @Test fun realWorkersFailInsteadOfThrowingWhenTheContainerIsUnavailable() {
        // The plain test Application has no AppContainer: the first access throws a ClassCastException.
        assertThat(execute<PlaylistSyncWorker>(SyncScheduler.KEY_SOURCE_ID to 1L)).isEqualTo(Result.failure())
        assertThat(execute<EpgSyncWorker>(SyncScheduler.KEY_EPG_ID to 1L)).isEqualTo(Result.failure())
        assertThat(execute<PruneWorker>()).isEqualTo(Result.failure())
    }
}
