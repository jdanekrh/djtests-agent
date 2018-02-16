import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import org.apache.felix.framework.Felix;
import org.glassfish.json.JsonProviderImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.regex.MatchResult;

// https://stackoverflow.com/questions/1887809/how-to-start-and-use-apache-felix-from-code
class Main {

    Felix osgi;

    Client aac;
    Client acc;
    Client aoc;

    Client aac5Sender;
    Client aac5Receiver;

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
        Server server = ServerBuilder.forPort(port)
                .addService(new RouteGuideService(app))
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

    private void installClients() {
        aac = installClient(
                "file:///home/jdanek/Work/repos/cli-java/cli-qpid-jms/target/cli-qpid-jms-1.2.2-SNAPSHOT-LATEST.jar",
                "com.redhat.mqe.jms.Main");
        acc = installClient(
                "file:///home/jdanek/Work/repos/cli-java/cli-artemis-jms/target/cli-artemis-jms-1.2.2-SNAPSHOT-LATEST.jar",
                "com.redhat.mqe.acc.Main");
        aoc = installClient(
                "file:///home/jdanek/Work/repos/cli-java/cli-activemq/target/cli-activemq-1.2.2-SNAPSHOT-LATEST.jar",
                "com.redhat.mqe.aoc.Main");

        aac5Sender = new PythonClient("aac5_sender.py");
        aac5Receiver = new PythonClient("aac5_receiver.py");
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
            return new Client(m);
        } catch (BundleException | ClassNotFoundException | NoSuchMethodException e1) {
            e1.printStackTrace();
        }
        return null;
    }

    void stopOsgi() {
        try {
            osgi.stop();
        } catch (BundleException e) {
            e.printStackTrace();
        }
    }
}

class RouteGuideService extends CliGrpc.CliImplBase {
    static final POSIX posix = POSIXFactory.getNativePOSIX();

    Main app;
    final JsonProviderImpl jsonProvider = new JsonProviderImpl();

    RouteGuideService(Main app) {
        this.app = app;
    }

    @Override
    public void runCli(CliRequest request, StreamObserver<CliReply> responseObserver) {
        ClientListener listener = new StringListener() {
            @Override
            public void onMessage(Map<String, Object> map) {
                String line = serializeMessage(map);
                responseObserver.onNext(CliReply.newBuilder().addLines(line).build());
            }

            @Override
            public void onMessage(String string) {
                responseObserver.onNext(CliReply.newBuilder().addLines(string).build());
            }

            @Override
            public void onStart(Process process) {
            }

            @Override
            public void onError(String s) {
                responseObserver.onNext(CliReply.newBuilder().setStderr(s).build());
            }
        };

        List<String> args = new ArrayList<>();
        args.add(request.getType());
        args.addAll(request.getOptionsList());

        int status = 1;
        switch (request.getCli()) {
            case "aac": {
                status = app.aac.run(listener, args);
                break;
            }
            case "acc": {
                status = app.acc.run(listener, args);
                break;
            }
            case "aoc": {
                status = app.aoc.run(listener, args);
                break;
            }
            case "aac5": {
//                List<String> sargs = args.subList(1, args.size() - 1); // todo
                switch (request.getType()) {
                    case "sender":
                        status = app.aac5Sender.runWrapped(request.getWrapperOptions(), listener, args);
                        break;
                    case "receiver":
                        status = app.aac5Receiver.runWrapped(request.getWrapperOptions(), listener, args);
                        break;
                    default:
                        throw new RuntimeException("Not implemented");
                }
            }
        }

        responseObserver.onNext(CliReply.newBuilder().setStatus(status).build());
        responseObserver.onCompleted();
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
                            try {
                                final Field field = process.getClass().getDeclaredField("pid");
                                field.setAccessible(true);
                                int pid = field.getInt(process);
                                System.out.println(pid);
                                synchronized (responseObserver) {
                                    responseObserver.onNext(CliReply.newBuilder().setCliId(CliID.newBuilder().setPid(pid).build()).build());
                                }
                            } catch (IllegalAccessException e) {
                                e.printStackTrace();
                            } catch (NoSuchFieldException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onError(String s) {
                            synchronized (responseObserver) {
                                responseObserver.onNext(CliReply.newBuilder().setStderr(s).build());
                            }
                        }
                    };

                    List<String> args = new ArrayList<>();
                    args.add(request.getType());
                    args.addAll(request.getOptionsList());

                    int status = 1;
                    switch (request.getCli()) {
                        case "aac": {
                            status = app.aac.run(listener, args);
                            break;
                        }
                        case "acc": {
                            status = app.acc.run(listener, args);
                            break;
                        }
                        case "aoc": {
                            status = app.aoc.run(listener, args);
                            break;
                        }
                        case "aac5": {
//                List<String> sargs = args.subList(1, args.size() - 1); // todo
                            switch (request.getType()) {
                                case "sender":
                                    status = app.aac5Sender.runWrapped(request.getWrapperOptions(), listener, args);
                                    break;
                                case "receiver":
                                    status = app.aac5Receiver.runWrapped(request.getWrapperOptions(), listener, args);
                                    break;
                                default:
                                    throw new RuntimeException("Not implemented");
                            }
                        }
                    }

                    responseObserver.onNext(CliReply.newBuilder().setStatus(status).build());
                    responseObserver.onCompleted();
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
            fileDoesNotExist(responseObserver);
            return;
        }
        long offset = file.length();
        responseObserver.onNext(OffsetReply.newBuilder().setOffset(offset).build());
        responseObserver.onCompleted();
    }

    void fileDoesNotExist(StreamObserver<?> responseObserver) {
        responseObserver.onError(Status.fromCode(Status.Code.INVALID_ARGUMENT).withDescription("File does not exist").asException());
    }

    @Override
    public void getSnap(SnapRequest request, StreamObserver<SnapReply> responseObserver) {
        Path path = Paths.get(request.getFile());
        long begin = request.getBegin();
        long end = request.getEnd();

        final File file = path.toFile();
        if (!file.exists()) {
            fileDoesNotExist(responseObserver);
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
            fileDoesNotExist(responseObserver);
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