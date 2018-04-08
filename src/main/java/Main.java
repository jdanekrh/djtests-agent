import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.*;
import conf.Cli;
import conf.Conf;
import conf.ConfParser;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.apache.felix.framework.Felix;
import org.glassfish.json.JsonProviderImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidParameterException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.MatchResult;

// https://stackoverflow.com/questions/1887809/how-to-start-and-use-apache-felix-from-code
class Main {

    Felix osgi;
    Conf cliConf;

    public static void main(String[] args) throws BundleException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Main app = new Main();

        app.startOsgi();
        app.installClients();

//        app.aac.run("sender", "--help");
//        app.aac.run("sender", "--log-msgs=dict", "--address=q");
//        app.acc.run("sender", "--log-msgs=dict", "--address=q");
//        app.aoc.run("sender", "--log-msgs=dict", "--address=q");
//        app.aac5Sender.run("--log-msgs=dict");
//        acc.run("sender", "--help");
//        aoc.run("sender", "--help");

        int port = 5555;
        Server server = NettyServerBuilder.forPort(port)
                .maxMessageSize(25 * 1024 * 1024)  // 25 MiB should be enough for everyone
                .addService(new CliService(app))
                .addService(new LogSnapperService())
                .build();

        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        app.stopOsgi();
    }

    void installClients() {
        try {
            cliConf = ConfParser.parseConf(Paths.get("clis.py"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse configuration", e);
        }
    }

    void startOsgi() {
        Map<String, String> configMap = new HashMap<>();
        configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "sun.misc,com.redhat.mqe");
        configMap.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        osgi = new Felix(configMap);
        try {
            osgi.init();
            osgi.start();
        } catch (BundleException e1) {
            e1.printStackTrace();
        }
    }

    Client installClient(String location, String main) {
        try {
            Bundle b = osgi.getBundleContext().installBundle(location);
            System.out.println("starting bundle " + b.getLocation());
            b.start();

            Class c = b.loadClass(main);
            Method m = c.getMethod("main", ClientListener.class, String[].class);
            return new OSGiClient(m);
        } catch (BundleException | ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException("Failed to install OSGi cli bundle", e);
        }
    }

    void stopOsgi() {
        try {
            osgi.stop();
        } catch (BundleException e) {
            e.printStackTrace();
        }
    }
}

class CliService extends CliGrpc.CliImplBase {
    static final POSIX posix = POSIXFactory.getNativePOSIX();

    Main app;
    final JsonProviderImpl jsonProvider = new JsonProviderImpl();

    CliService(Main app) {
        this.app = app;
    }

    @Override
    public void runCli(CliRequest request, StreamObserver<CliReply> responseObserver) {
    }

    @Override
    public StreamObserver<CliRequest> runKillableCli(StreamObserver<CliReply> responseObserver) {
        final Thread[] t = new Thread[1];
        return new StreamObserver<CliRequest>() {
            @Override
            public void onNext(CliRequest request) {
                t[0] = new Thread(() -> {
                    ClientListener listener = new StringListener() {
                        @Override
                        public void onMessage(Map<String, Object> map) {
                            String line = serializeMessage(map);
                            synchronized (responseObserver) {
                                responseObserver.onNext(CliReply.newBuilder().addLines(line).build());
                            }
                        }

                        @Override
                        public void onMessage(String string) {
                            synchronized (responseObserver) {
                                responseObserver.onNext(CliReply.newBuilder().addLines(string).build());
                            }
                        }

                        @Override
                        public void onStart(Process process) {
                            int pid = ProcessManagement.getPid(process);
                            System.out.println(pid);
                            synchronized (responseObserver) {
                                responseObserver.onNext(CliReply.newBuilder().setCliId(CliID.newBuilder().setPid(pid).build()).build());
                            }
                        }

                        @Override
                        public void onError(String s) {
                            synchronized (responseObserver) {
                                responseObserver.onNext(CliReply.newBuilder().setStderr(s).build());
                            }
                        }
                    };

                    Optional<Cli> cliOpt = app.cliConf.getCli(request.getCli(), request.getType());
                    if (cliOpt.isPresent()) {
                        Cli cli = cliOpt.get();
                        SubprocessClient client = new SubprocessClient(new File(cli.directory), cli.prefix_args);
                        int status = client.runWrapped(request.getWrapperOptions(), listener, request.getOptionsList());
                        responseObserver.onNext(CliReply.newBuilder().setStatus(status).build());
                        responseObserver.onCompleted();
                        return;
                    }
                    responseObserver.onError(Status.NOT_FOUND.withDescription("Cli was not found").asException());
                });
                t[0].start();
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onCompleted() {
                t[0].interrupt();
            }
        };
    }

    @Override
    public void waitForConnection(CliID request, StreamObserver<CliStatus> responseObserver) {
        SS socketStatus = new SS();

        Duration timeout = Duration.of(300, ChronoUnit.SECONDS);  // upper limit on timeout

        Instant whence = Instant.now();
        while (!((ServerCallStreamObserver) responseObserver).isCancelled() && Duration.between(whence, Instant.now()).compareTo(timeout) < 0) {
            try {
                List<SocketInfo> sockets = socketStatus.listSocketsForPid(String.valueOf(request.getPid()));
//                System.out.println(sockets);
                if (sockets.stream().allMatch((socket) -> socket.getStatus().equals("ESTABLISHED"))) {
                    responseObserver.onNext(CliStatus.newBuilder().setRunning(true).setConnected(true).setConnectionCount(sockets.size()).build());
                    responseObserver.onCompleted();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (NoSuchFileException ignored) {
                responseObserver.onNext(CliStatus.newBuilder().setRunning(false).build());
                responseObserver.onCompleted();
                return;
            } catch (IOException e) {
                responseObserver.onError(Status.INTERNAL.withCause(e).withDescription("Reading socket status failed").asException());
                responseObserver.onCompleted();
                return;
            }
        }
        responseObserver.onNext(CliStatus.newBuilder().setRunning(true).setConnected(false).build());
    }

    @Override
    public void kill(CliKillRequest request, StreamObserver<CliStatus> responseObserver) {
        int pid = request.getCliId().getPid();
        if (pid == 0) {
            responseObserver.onError(new InvalidParameterException("Will not kill pid=0"));
            return;
        }
        System.out.println("killing " + pid);
        posix.kill(request.getCliId().getPid(), request.getSignal());
        responseObserver.onNext(CliStatus.newBuilder().build());
        responseObserver.onCompleted();
    }

    private String serializeMessage(Map<String, Object> map) {
        return jsonProvider.createObjectBuilder(map).build().toString();
    }
}

class LogSnapperService extends LogSnapperGrpc.LogSnapperImplBase {
    @Override
    public void getOffset(OffsetRequest request, StreamObserver<OffsetReply> responseObserver) {
        Path path = Paths.get(request.getFile());
        final File file = path.toFile();
        if (!file.exists()) {
            fileDoesNotExist(request.getFile(), responseObserver);
            return;
        }
        long offset = file.length();
        responseObserver.onNext(OffsetReply.newBuilder().setOffset(offset).build());
        responseObserver.onCompleted();
    }

    void fileDoesNotExist(String file, StreamObserver<?> responseObserver) {
        responseObserver.onError(
                Status.fromCode(Status.Code.INVALID_ARGUMENT).withDescription("File '" + file + "' does not exist").asException());
    }

    @Override
    public void getSnap(SnapRequest request, StreamObserver<SnapReply> responseObserver) {
        Path path = Paths.get(request.getFile());
        long begin = request.getBegin();
        long end = request.getEnd();

        final File file = path.toFile();
        if (!file.exists()) {
            fileDoesNotExist(request.getFile(), responseObserver);
            return;
        }

        long size = computeSize(file, begin, end);
        CharBuffer b = CharBuffer.allocate((int) size);


        try {
            try (FileReader r = new FileReader(file)) {
                long skipped = r.skip(begin);
                if (skipped != begin) {
                    responseObserver.onError(Status.DATA_LOSS.withDescription("Cannot skip to beginning of snap").asException());
                    return;
                }
                while (size > 0) {
                    int read = r.read(b);
                    if (read == -1) {
                        responseObserver.onError(Status.DATA_LOSS.withDescription("Premature end of stream reached while reading").asException());
                        return;
                    }
                    size -= read;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        b.flip();
        String snap = b.toString();

        responseObserver.onNext(SnapReply.newBuilder().setSnap(snap).build());
        responseObserver.onCompleted();
    }

    long computeSize(File file, long begin, long end) {
        long size = end - begin;
        // todo decide on single special value for end
        if (end <= 0) {
            size = file.length() - begin;
        }
        return size;
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchReply> responseObserver) {
        SnapRequest snapRequest = request.getSnap();
        List<String> strings = new ArrayList<>(request.getStringsList());

        final Path path = Paths.get(snapRequest.getFile());
        final File file = path.toFile();
        final long begin = snapRequest.getBegin();
        final long end = snapRequest.getEnd();

        if (!file.exists()) {
            fileDoesNotExist(snapRequest.getFile(), responseObserver);
            return;
        }

        long size = computeSize(file, begin, end);

        try (InputStream is = new FileInputStream(file)) {
            is.skip(begin);
            boolean result = findStringsInStream(strings, (int) size, is);
            responseObserver.onNext(SearchReply.newBuilder().setFound(result).build());
            responseObserver.onCompleted();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    boolean findStringsInStream(List<String> strings, int size, InputStream is) {
        Scanner s = new Scanner(is);
        for (String string : strings) {
            String result = s.findWithinHorizon(string, size);
            if (result == null) {
                return false;
            }
            // decrease the horizon for the next call
            MatchResult match = s.match();
            size -= match.end();
        }
        return true;
    }
}