package conf;

import com.google.devtools.build.lib.events.PrintingEventHandler;
import com.google.devtools.build.lib.syntax.*;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

public class ConfParser {
    public static Conf parseConf(Path path) throws Exception {

        final String string = Files.readAllLines(path).stream()
                .filter((line) -> !line.startsWith("import ") && !line.startsWith("from "))
                .collect(Collectors.joining("\n"));
        ParserInputSource source = ParserInputSource.create(string, PathFragment.create(path.toString()));
        BuildFileAST result = BuildFileAST.parseSkylarkFile(source, PrintingEventHandler.ERRORS_AND_WARNINGS_TO_STDERR);

        SkylarkSignatureProcessor.configureSkylarkFunctions(Conf.Module.class);
        SkylarkSignatureProcessor.configureSkylarkFunctions(Cli.Module.class);

        final Environment env = Environment.builder(Mutability.create("myMutability"))
                .useDefaultSemantics()
                .setEventHandler(PrintingEventHandler.ERRORS_AND_WARNINGS_TO_STDERR)
                .build();
        Runtime.setupModuleGlobals(env, Conf.Module.class);
        Runtime.setupModuleGlobals(env, Cli.Module.class);

        Conf.Module conf = (Conf.Module) env.getGlobals().get(Conf.Module.CONF_VAR);

        result.eval(env);
        env.mutability().close();

        env.getGlobals().getBindings().forEach((k, v) -> {
            System.out.println(k + ": " + v.toString());
        });

        System.out.println(conf.conf);
        return conf.conf;
    }
}
