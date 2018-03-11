package conf;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.*;
import com.google.devtools.build.lib.syntax.*;
import com.google.devtools.build.lib.syntax.Runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

public class Conf {
    private LinkedHashMap<CliCoord, Cli> clis = new LinkedHashMap<>();

    private void addCli(Location location, String name, SkylarkList<String> tags, String kind, Cli cli) {
        LinkedHashSet<String> tagSet = new LinkedHashSet<>(tags);
        final CliCoord key = new CliCoord(name, tagSet, kind);
        if (clis.containsKey(key)) {
            throw new RuntimeException("Duplicated client declaration found, " + key);
        }
        clis.put(key, cli);
    }

    public Optional<Cli> getCli(String name, String kind) {
        return clis.entrySet().stream()
                .filter((e) -> {
                    CliCoord key = e.getKey();
                    return key.name.equals(name) && key.kind.equals(kind);
                })
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @Override
    public String toString() {
        return "Conf{" +
                "clis=" + clis +
                '}';
    }

    @SkylarkModule(
            name = Module.CONF_VAR,
            doc = "Main configuration object",
            category = SkylarkModuleCategory.BUILTIN)
    public static final class Module {
        public static final String CONF_VAR = "conf";

        @SuppressWarnings("unused")
        @SkylarkSignature(name = "cli", returnType = Runtime.NoneType.class,
                doc = "Registers a cli (messaging test client) which can be then invoked through a RPC.",
                parameters = {
                        @Param(name = "self", type = Module.class, doc = "this object"),
                        @Param(name = "name", type = String.class,
                                doc = "A name of the cli used for referencing it in invocation, e.g. 'aac1'."),
                        @Param(name = "tags", type = SkylarkList.class, generic1 = String.class,
                                doc = "List of tags, recommended tag format is 'key:value'.",
                                defaultValue = "[]")
                },
                objectType = Module.class,
                useLocation = true,
                extraKeywords = @Param(
                        name = "kwargs", type = SkylarkDict.class, generic1 = Cli.class,
                        doc = "Dictionary of clis, keyed on their kind (such as 'sender' or 'connector')"
                ))
        static final BuiltinFunction CLI = new BuiltinFunction("cli") {
            public Runtime.NoneType invoke(Module self, String name, SkylarkList<String> tags, SkylarkDict<String, Cli> kwargs, Location location) throws EvalException {
                kwargs.forEach((kind, cli) -> self.conf.addCli(location, name, tags, kind, cli));
                return Runtime.NONE;
            }
        };

        Conf conf = new Conf();

        @SkylarkCallable(name = "environ", structField = true, doc = "The list of environment variables.")
        public ImmutableMap<String, String> getEnvironmentVariables() {
            return ImmutableMap.<String, String>builder().build();
        }
    }
}