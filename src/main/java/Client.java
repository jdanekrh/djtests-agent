import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.TemporaryFile;
import com.redhat.mqe.djtests.cli.WrapperOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Client {
    private final Method m;

    Client(Method m) {
        this.m = m;
    }

    int run(ClientListener listener, List<String> args) {
        return run(listener, args.toArray(new String[0]));
    }

    int run(String... args) {
        return run(null, args);
    }

    public int run(ClientListener listener, String... args) {
        return runWrapped(null, listener, Arrays.asList(args));
    }

    public int runWrapped(WrapperOptions wrapperOptions, ClientListener listener, List<String> args) {
        Object[] array = new Object[]{listener, args.toArray(new String[0])};

        SecurityManager previousManager = System.getSecurityManager();
        try {
            SecurityManager manager = new ExitSecurityManager(previousManager);
            System.setSecurityManager(manager);

            m.invoke(null, array);
        } catch (SystemExitingWithStatus e) {
            return e.status;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SystemExitingWithStatus) {
                return ((SystemExitingWithStatus) e.getCause()).status;
            }
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } finally {
            System.setSecurityManager(previousManager);
        }
        return 0;
    }
}

class JavaClient extends SubprocessClient {
    JavaClient(String file, String type) {
        super(null);
        directory = Paths.get("/home/jdanek/Work/repos/cli-java").toFile();
        prefixArgs = Arrays.asList("java", "-jar", file);
    }
}

class PythonClient extends SubprocessClient {
    private String file;

    PythonClient(String file) {
        super(null);
        this.file = file;

        directory = Paths.get("/home/jdanek/Work/repos/dtests/dtests/node_data/clients/python").toFile();
        prefixArgs = Arrays.asList("/home/jdanek/.virtualenvs/p2dtests/bin/python2", file);
    }
}

class RubyClient extends SubprocessClient {
    private String file;

    RubyClient(String file) {
        super(null);
        this.file = file;

        directory = Paths.get("/home/jdanek/Work/repos/cli-proton-ruby/bin").toFile();
        prefixArgs = Arrays.asList("ruby", file);
    }
}

abstract class SubprocessClient extends Client {
    File directory;
    List<String> prefixArgs;

    SubprocessClient(Method m) {
        super(m);
    }

    @Override
    public int run(ClientListener listener, String... args) {
        return runWrapped(null, listener, Arrays.asList(args));
    }

    public int runWrapped(WrapperOptions wrapperOptions, ClientListener listener, List<String> args) {
        int c_killtime = 100;
        Map<Integer, Path> temporaryFiles = new HashMap<>();
        try {
            if (wrapperOptions != null) {
                if (wrapperOptions.hasCKilltime()) {
                    c_killtime = wrapperOptions.getCKilltime().getValue();
                }
                for (TemporaryFile tf : wrapperOptions.getTempFileList()) {
                    try {
                        Path p = Files.createTempFile(null, null);
                        temporaryFiles.put(tf.getId(), p);
                        Files.write(p, tf.getContent().toByteArray());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            final String prefix = "temporaryFile://";
            List<String> arguments = args.stream()
                    .map((a) -> {
                        if (a.startsWith(prefix)) {
                            int id = Integer.valueOf(a.substring(prefix.length()));
                            return temporaryFiles.get(id).toString();
                        }
                        return a;
                    }).collect(Collectors.toList());
            try {
                List<String> command = new ArrayList<>();
                command.addAll(prefixArgs);
                command.addAll(arguments);
                // dTests tests expect commands will go through shell; the escaping should be part of dTests itself
//                List<String> shellCommand = Arrays.asList("sh", "-c", String.join(" ", command));
                ProcessBuilder pb = new ProcessBuilder()
                        .command(command)
                        .directory(directory);
                Map<String, String> env = pb.environment();
                env.put("PYTHONUNBUFFERED", "1");
                System.out.println(pb.command());
                Process p = pb.start();
                ((StringListener) listener).onStart(p);
                Thread t = new Thread(() -> {
                    try (BufferedReader bis = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        bis.lines().forEach((line) -> {
                            System.out.println(line);
                            if (listener != null) {
                                ((StringListener) listener).onMessage(line); // todo
                            }
                        });
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                t.start();
                Thread u = new Thread(() -> {
                    try (BufferedReader bis2 = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                        bis2.lines().forEach((line) -> {


                            System.out.println(line);
                            if (listener != null) {
                                listener.onError(line);
                            }
                        });
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                u.start();
                try {
                    p.waitFor(c_killtime, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // client run was cancelled
                    e.printStackTrace();
                }
                p.destroy();
                boolean exited = false;
                try {
                    exited = p.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (!exited) {
                    p.destroyForcibly();
                }
                try {
                    t.join();
                    u.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return p.exitValue();
            } catch (SystemExitingWithStatus e) {
                return e.status;
            } catch (IOException e) {
                e.printStackTrace();
            }
            return 0;
        } finally {
            for (Path p : temporaryFiles.values()) {
                boolean ok = p.toFile().delete();
                if (!ok) {
                    System.out.println("Failed to delete temporary file " + p);
                }
            }
        }
    }
}

class SystemExitingWithStatus extends RuntimeException {
    public final int status;

    SystemExitingWithStatus(int status) {
        this.status = status;
    }
}


class ExitSecurityManager extends SecurityManager {
    private SecurityManager parentManager;

    ExitSecurityManager(SecurityManager parentManager) {
        this.parentManager = parentManager;
    }

    @Override
    public void checkExit(int status) {
        throw new SystemExitingWithStatus(status);
    }

    @Override
    public void checkPermission(Permission perm) {
        if (parentManager != null) {
            parentManager.checkPermission(perm);
        }
    }

}
