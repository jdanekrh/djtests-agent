import jnr.constants.platform.Signal
import jnr.posix.POSIXFactory
import jnr.posix.SignalHandler
import jnr.posix.util.Platform.IS_WINDOWS
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

val posix = POSIXFactory.getNativePOSIX()

class PosixTests {
    @Test
    fun testBasicSignal() {
        if (!IS_WINDOWS) {
            val s = Signal.SIGHUP
            val fired = AtomicBoolean(false)
            posix.signal(s, SignalHandler() {
                println("Got signal")
            })

            posix.kill(posix.getpid(), s.intValue())
        }
    }
}
