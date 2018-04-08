import com.google.common.base.Strings;
import com.google.common.collect.Streams;
import jnr.posix.POSIXFactory;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class SocketInfo {
    String localIP;
    int localPort;
    String remoteIP;
    int remotePort;
    int Uuind;
    int socketInode;
    int socketReference;
    long socketPointer;
    int connectionState;

    String getStatus() {
        // https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/include/net/tcp_states.h?id=HEAD
        final String[] statuses = {
                "",
                "ESTABLISHED", // - 1
                "SYN_SENT",
                "SYN_RECV",
                "FIN_WAIT1",
                "FIN_WAIT2",
                "TIME_WAIT",
                "CLOSE",
                "CLOSE_WAIT",
                "LAST_ACK",
                "LISTEN",
                "CLOSING",
                "TCP_NEW_SYN_RECV",
        };

        return statuses[connectionState];
    }

    @Override
    public String toString() {
        return localIP + ":" + localPort + " -> " + remoteIP + ":" + remotePort + " [" + getStatus() + "]";
    }
}

public class SS {
    static final Pattern socketLinkPattern = Pattern.compile("socket:\\[(\\d+)\\]");

    List<SocketInfo> getSS() {
        // http://search.cpan.org/~salva/Linux-Proc-Net-TCP-0.07/lib/Linux/Proc/Net/TCP.pm
        // http://cpansearch.perl.org/src/SALVA/Linux-Proc-Net-TCP-0.07/lib/Linux/Proc/Net/TCP.pm
        // https://stackoverflow.com/questions/23769143/list-open-tcp-connections-in-java
        final Path tcp4 = Paths.get("/proc/net/tcp");
        final Path tcp6 = Paths.get("/proc/net/tcp6");
        try {
            //        try (BufferedInputStream bfs = new BufferedInputStream(FileReader fr = new FileReader(ss))) {
            Stream<String> ipv4 = Files.readAllLines(tcp4).stream().skip(1);
            Stream<String> ipv6 = Files.readAllLines(tcp6).stream().skip(1);

            return Streams.concat(ipv4, ipv6)
                    .map((line) -> parseSS(line))
                    .filter(Objects::nonNull)  // last line of file is a /n
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("getting SS failed");
        }
    }

    @Nullable
    SocketInfo parseSS(String line) {
        // consider using re2, just for comfort
        Pattern p = Pattern.compile("\\s*(\\d+):" + Strings.repeat(" +(\\S+)", 16) + "\\s*");
        Matcher m = p.matcher(line);
        if (m.matches()) {
            int no = Integer.parseInt(m.group(1), 10);

            String[] lipp = m.group(2).split(":");
            String localIP = parseIP(lipp[0]);
            int localPort = Integer.parseInt(lipp[1], 16);

            String[] ripp = m.group(3).split(":");
            String remoteIP = parseIP(ripp[0]);
            int remotePort = Integer.parseInt(ripp[1], 16);

            int connectionState = Integer.parseInt(m.group(4), 16);

            int uuid = Integer.parseInt(m.group(8), 10);
            int socketInode = Integer.parseInt(m.group(10), 10);
            int socketReference = Integer.parseInt(m.group(11), 10);
            long socketPointer = Long.parseUnsignedLong(m.group(12), 16);

            SocketInfo ss = new SocketInfo();
            ss.localIP = localIP;
            ss.localPort = localPort;
            ss.remoteIP = remoteIP;
            ss.remotePort = remotePort;
            ss.connectionState = connectionState;
            ss.Uuind = uuid;
            ss.socketInode = socketInode;
            ss.socketReference = socketReference;
            ss.socketPointer = socketPointer;

            return ss;
        }
        return null;
    }

    List<SocketInfo> listSocketsForPid(String pid) throws IOException {
        final List<String> sockets = listSocketFDsForPid(pid);
        List<SocketInfo> ss = getSS();
        return ss.stream().filter((s) -> sockets.contains(Integer.toString(s.socketInode))).collect(Collectors.toList());
    }

    List<String> listSocketFDsForPid(String pid) throws IOException {
        List<String> sockets = new ArrayList<>();
        Files.list(Paths.get("/proc/" + pid + "/fd")).forEach((fd) -> {
            try {
                String target = POSIXFactory.getNativePOSIX().readlink(fd.toString());
                Matcher m = socketLinkPattern.matcher(target);
                if (m.matches()) {
                    sockets.add(m.group(1));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        return sockets;
    }

    static String parseIP(String hex) {
        if (hex.matches("0*")) {
            return "*";
        }
        if (hex.length() == 4 * 8) {
            final String prefix = "0000000000000000FFFF0000";
            if (hex.startsWith(prefix)) {
                // https://stackoverflow.com/questions/7717390/converting-hex-to-ipv6-format-in-java
                // ipv6-mapped ipv4 address
                StringBuilder ip = new StringBuilder();
                boolean first = true;
                for (int i = 4 * 8; i > prefix.length(); i -= 2) {
                    if (!first) {
                        ip.append(".");
                    }
                    ip.append(Integer.parseInt(hex.substring(i - 2, i), 16));
                    first = false;
                }
                return ip.toString();
            }
            // ipv6
            StringBuilder ip = new StringBuilder();
            boolean first = true;
            for (int i = 0; i < hex.length(); i += 4) {
                if (!first) {
                    ip.append(":");
                }
                ip.append(hex.substring(i, i + 4));
                first = false;
            }
            return ip.toString();

        }
        // ipv4
        StringBuilder ip = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < hex.length(); i += 2) {
            if (!first) {
                ip.append(".");
            }
            ip.append(Integer.parseInt(hex.substring(i, i + 2), 16));
            first = false;
        }
        return ip.toString();
    }
}
