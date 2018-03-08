import org.junit.Assert;
import org.junit.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.regex.Matcher;

public class SSTests {
    private final SS socketStat = new SS();

    @Test
    public void testSSLike() {
        String line = " 0: 00000000:AF35 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 13201325 1 0000000000000000 100 0 0 10 0   ";
        SocketInfo socketInfo = socketStat.parseSS(line);
        Assert.assertNotNull(socketInfo);
        Assert.assertEquals("*", socketInfo.localIP);
        Assert.assertEquals(44853, socketInfo.localPort);
        Assert.assertEquals("*", socketInfo.remoteIP);
        Assert.assertEquals(0, socketInfo.remotePort);
        Assert.assertEquals("LISTEN", socketInfo.getStatus());
    }

    @Test
    public void testGetSS() {
        List<SocketInfo> ss = socketStat.getSS();
        for (SocketInfo s : ss) {
            System.out.println(s);
        }
    }

    @Test
    public void testListSocketFDsForNoSuchProcess() throws Exception {
        try {
            socketStat.listSocketFDsForPid("noSuchProcess");
        } catch (NoSuchFileException ignored) {
        }
    }

    @Test
    public void testListProcessSockets() {
        String result = "socket:[13201325]";
        Matcher m = SS.socketLinkPattern.matcher(result);
        if (m.matches()) {
            System.out.println("matched " + m.group(1));
        }
    }

    @Test
    public void testCheckOwnPorts() throws Exception {
        List<SocketInfo> initial = socketStat.listSocketsForPid("self");

        ServerSocket server = new ServerSocket(0);
        server.setReuseAddress(true);
        int port = server.getLocalPort();

        List<SocketInfo> before = socketStat.listSocketsForPid("self");
        Assert.assertEquals(initial.size() + 1, before.size());

        Socket client = new Socket("127.0.0.1", port);

        List<SocketInfo> after = socketStat.listSocketsForPid("self");
        Assert.assertEquals(initial.size() + 2, after.size());

        System.out.println(initial);
        System.out.println(before);
        System.out.println(after);

        client.close();
        server.close();
    }
}
