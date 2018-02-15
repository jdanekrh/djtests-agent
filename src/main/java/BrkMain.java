import com.google.protobuf.Empty;
import com.redhat.mqe.djtests.cli.BrkGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class BrkMain {
    public static void main(String[] args) {
        final BrkService brkService = new BrkService("/home/jdanek/Downloads/AMQ7/7.1.0/cr2.2/amq-broker-7.1.0/i0/");
        Server server = ServerBuilder.forPort(6666)
                .addService(brkService)
                .build();

        brkService.startUp();

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
    }
}

class BrkService extends BrkGrpc.BrkImplBase {
    private final String artemisInstance;
    private Process artemisProcess;
    private Thread artemisThread;

    BrkService(String artemisInstance) {
        this.artemisInstance = artemisInstance;
    }

    public void startUp() {
        ProcessBuilder pb = new ProcessBuilder()
                .directory(new File(artemisInstance))
                .command("bin/artemis", "run");
        pb.redirectErrorStream(true);
        try {
            artemisProcess = pb.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        artemisThread = new Thread(() -> {
            try {
                try (BufferedReader bis = new BufferedReader(new InputStreamReader(artemisProcess.getInputStream()))) {
                    bis.lines().forEach((line) -> {
                        System.out.println(line);
                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        artemisThread.start();
    }

    @Override
    public void sigKill(Empty request, StreamObserver<Empty> responseObserver) {
        System.out.println("KILLING");
        artemisProcess.destroyForcibly();
        try {
            artemisThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void sigTerm(Empty request, StreamObserver<Empty> responseObserver) {
        artemisProcess.destroy();
        try {
            artemisThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        responseObserver.onCompleted();
    }

    @Override
    public void start(Empty request, StreamObserver<Empty> responseObserver) {
        startUp();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }
}

