import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Empty;
import com.redhat.mqe.djtests.cli.BrkGrpc;
import com.redhat.mqe.djtests.cli.PatchRequest;
import com.redhat.mqe.djtests.cli.RestoreRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class BrkMain {
    public static void main(String[] args) {
        final Path artemisInstance = Paths.get("/home/jdanek/Downloads/AMQ7/7.1.0/cr2.2/amq-broker-7.1.0/i0/");
        final BrkService brkService = new BrkService(artemisInstance);
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
    private final Path artemisInstance;
    private Process artemisProcess;
    private Thread artemisThread;

    BrkService(Path artemisInstance) {
        this.artemisInstance = artemisInstance;
    }

    public void startUp() {
        ProcessBuilder pb = new ProcessBuilder()
                .directory(artemisInstance.toFile())
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

    @Override
    public void restoreFile(RestoreRequest request, StreamObserver<Empty> responseObserver) {
        Path path = artemisInstance.resolve(request.getPath());
        try {
            Files.copy(path.getParent().resolve("bck").resolve(path.getFileName()), path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.NOT_FOUND.withDescription("Copying failed").withCause(e).asException());
            return;
        }
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void patchFile(PatchRequest request, StreamObserver<Empty> responseObserver) {
        Path path = artemisInstance.resolve(request.getPath());
        String original = null;
        try {
            original = new String(Files.readAllBytes(path));
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.NOT_FOUND.withCause(e).asException());
        }
        Map<String, Object> patch = null;
        try {
            patch = new ObjectMapper().readValue(request.getJson(), Map.class);
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withCause(e).asException());
            return;
        }

        final XmlPatcher patcher = new XmlPatcher();
        String patched = patcher.patch(path, patch);
        patcher.printColorDiff(original, patched);
        try {
            Files.write(path, patched.getBytes());
        } catch (IOException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT.withCause(e).asException());
            return;
        }
        responseObserver.onNext(Empty.newBuilder().build());
        responseObserver.onCompleted();
    }
}