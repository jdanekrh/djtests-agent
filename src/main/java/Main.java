import com.google.protobuf.ProtocolStringList;
import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.CliGrpc;
import com.redhat.mqe.djtests.cli.CliReply;
import com.redhat.mqe.djtests.cli.CliRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.glassfish.json.JsonProviderImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import javax.json.Json;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

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

        app.osgi.getBundleContext().installBundle("http://central.maven.org/maven2/org/apache/karaf/jndi/org.apache.karaf.jndi.core/4.1.3/org.apache.karaf.jndi.core-4.1.3.jar");

//        app.aac.run("sender", "--help");
//        app.aac.run("sender", "--log-msgs=dict", "--address=q");
//        app.acc.run("sender", "--log-msgs=dict", "--address=q");
//        app.aoc.run("sender", "--log-msgs=dict", "--address=q");
//        app.aac5.run("--log-msgs=dict");
//        acc.run("sender", "--help");
//        aoc.run("sender", "--help");

        int port = 5555;
        Server server = ServerBuilder.forPort(port).addService(new RouteGuideService(app)).build();

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
            public void onError(String s) {
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
                        status = app.aac5Sender.run(listener, args);
                        break;
                    case "receiver":
                        status = app.aac5Receiver.run(listener, args);
                        break;
                    default:
                        throw new RuntimeException("Not implemented");
                }
            }
        }

        responseObserver.onNext(CliReply.newBuilder().setStatus(status).build());
        responseObserver.onCompleted();
    }

    private String serializeMessage(Map<String, Object> map) {
        return jsonProvider.createObjectBuilder(map).build().toString();
    }
}
