import com.redhat.mqe.ClientListener;
import com.redhat.mqe.djtests.cli.WrapperOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        Object[] array = new Object[]{listener, args.toArray()};

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

class PythonClient extends Client {
    private final String file;

    PythonClient(String file) {
        super(null);
        this.file = file;
    }

    @Override
    public int run(ClientListener listener, String... args) {
        return runWrapped(null, listener, Arrays.asList(args));
    }

    public int runWrapped(WrapperOptions wrapperOptions, ClientListener listener, List<String> args) {
        int c_killtime = 100;
        if (wrapperOptions != null) {
            if (wrapperOptions.hasCKilltime()) {
                c_killtime = wrapperOptions.getCKilltime().getValue();
            }
        }
        try {
            List<String> command = new ArrayList<>();
            command.addAll(Arrays.asList("/home/jdanek/.virtualenvs/p2dtests/bin/python2", file));
            command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder()
                    .command(command)
                    .directory(Paths.get("/home/jdanek/Work/repos/dtests/dtests/node_data/clients/python").toFile());
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
            boolean exited = p.waitFor(1, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
            }
            t.join();
            u.join();
            return p.exitValue();
        } catch (SystemExitingWithStatus e) {
            return e.status;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 0;
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
