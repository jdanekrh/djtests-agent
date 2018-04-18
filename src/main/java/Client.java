import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.TemporaryFile;
import com.redhat.mqe.djtests.cli.WrapperOptions;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

interface Client {
//    default int run(com.redhat.mqe.ClientListener listener, List<String> args) {
//        return run(listener, args.toArray(new String[0]));
//    }
//
//    default int run(String... args) {
//        return run(null, args);
//    }

    default int run(ClientListener listener, String... args) {
        return runWrapped(null, listener, Arrays.asList(args));
    }

    int runWrapped(WrapperOptions wrapperOptions, ClientListener listener, List<String> args);
}

class OSGiClient implements Client {
    private final Method m;

    OSGiClient(Method m) {
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

class SubprocessClient implements Client {
    File directory;
    Map<String, String> environment;
    List<String> prefixArgs;

    SubprocessClient(File directory, Map<String, String> environment, List<String> prefixArgs) {
        this.directory = directory;
        this.environment = environment;
        this.prefixArgs = prefixArgs;
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
                env.putAll(environment);
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
                    } catch (UncheckedIOException | IOException e) {
                        System.err.println(e.getMessage());
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
                    } catch (UncheckedIOException | IOException e) {
                        System.err.println(e.getMessage());
                    }
                });
                u.start();
                try {
                    p.waitFor(c_killtime, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // client run was cancelled
                    e.printStackTrace();
                }
                ensureProcessIsDestroyed(p);  // TODO(jdanek) we could sigterm the process, java cannot do graceful
                t.interrupt();
                u.interrupt();
                while (true) {  // ensure we always join our threads
                    try {
                        t.join();  // the threads were interrupted, so the call is not supposed to block
                        u.join();
                        break;
                    } catch (InterruptedException e) {
                        // never seen this happen
                        // we are exiting anyways now, so ok to swallow
                        e.printStackTrace();
                    }
                }
                try {
                    return p.exitValue();
                } catch (IllegalThreadStateException e) {
                    e.printStackTrace();
                    return 150;  // we were not able to get that process to die
                }
            } catch (SystemExitingWithStatus e) {  // TODO(jdanek): this is needed only for OSGI, I think
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

    /**
     * First thing this method does is a p.waitFor. This is critical so that we do not destroy a process before the
     * background thread had a chance to get to the end of its output. TODO(jdanek): this asks for explicit sync
     *
     * @return whether the process was successfully destroyed
     */
    boolean ensureProcessIsDestroyed(Process p) {
        for (int i = 0; i < 2; i++) {
            boolean firstTime = i == 0;
            boolean exited = false;
            try {
                exited = p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (exited) {
                p.destroy();  // this is important, otherwise I have hanging output-reading threads later (linux)
                return true;
            }
            if (firstTime) {
                p.destroy();
            } else {
                p.destroyForcibly();
            }
        }
        return false;
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
