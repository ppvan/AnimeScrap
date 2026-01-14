import com.talent.animescrapsources.animesources.AllAnimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MyCoroutineTest {
    @Test
    fun mySuspendingFunction_performsAsExpected() = runTest {
        val source = AllAnimeSource()

        val result = source.latestAnime()

        print(result)

        assertEquals(1, 1)
    }

}
