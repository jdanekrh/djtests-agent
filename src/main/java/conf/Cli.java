package conf;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;

import java.util.List;

@SkylarkModule(
        name = "cli_class",
        namespace = true,
        doc = "Information about a cli.",
        category = SkylarkModuleCategory.BUILTIN)
public class Cli {
    // https://github.com/google/copybara/blob/aa6e6ac49bb3778259f766660b6150aadc9b8081/java/com/google/copybara/authoring/Authoring.java
    public final String directory;
    public final List<String> prefix_args;

    public Cli(String directory, List<String> prefix_args) {
        this.directory = directory;
        this.prefix_args = prefix_args;
    }

    @Override
    public String toString() {
        return "Cli{" +
                "directory='" + directory + '\'' +
                ", prefix_args=" + prefix_args +
                '}';
    }

    @SkylarkModule(
            name = "cli",
            namespace = true,
            doc = "Information about a cli.",
            category = SkylarkModuleCategory.BUILTIN)
    public static final class Module {
        @SkylarkSignature(name = "new_cli", returnType = Cli.class,
                doc = "Create a new cli configuration.",
                parameters = {
                        @Param(name = "directory", type = String.class,
                                doc = "Directory in which to execute the client"),
                        @Param(name = "prefix_args", type = SkylarkList.class, generic1 = String.class,
                                doc = "List of arguments to prefix for the execution, has to include at least path to the binary")
                }, useLocation = true)
        public static final BuiltinFunction NEW_CLI = new BuiltinFunction("new_cli") {
            public Cli invoke(String directory, SkylarkList<String> prefix_args, Location location)
                    throws EvalException {
                return new Cli(directory, prefix_args);
            }
        };
    }
}
