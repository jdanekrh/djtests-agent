import org.junit.Assert
import org.junit.Test

class Clients {
    @Test
    fun runOsgiClients() {
        val app = Main()

        app.startOsgi()
        app.installClients()

        var started = false
        val messages = ArrayList<String>()
        val listener = object : StringListener {
            override fun onMessage(string: String?) {
                messages.add(string!!)
            }

            override fun onMessage(p0: MutableMap<String, Any>?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onError(p0: String?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onStart(process: Process?) {
                started = true
            }
        }

        app.aac.run("sender", "--help")
        app.aac.run("sender", "--log-msgs=dict", "--address=q")
        // Broken due to java 9 modules, java.sql
//        app.acc.run("sender", "--log-msgs=dict", "--address=q")
        app.aoc.run("sender", "--log-msgs=dict", "--address=q")

        app.aac5Sender.run(listener, "--log-msgs=dict", "--msg-id=someMsgId")
        Assert.assertTrue(started)
        Assert.assertTrue(messages[0].contains("someMsgId"))

        app.stopOsgi()
    }
}