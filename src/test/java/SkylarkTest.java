import conf.Conf;
import conf.ConfParser;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SkylarkTest {
    @Test
    public void testSkylark() throws Exception {
        final Path path = Paths.get(getClass().getResource("test.skylark").toURI());
        Conf conf = ConfParser.parseConf(path);
        System.out.println(conf.getCli("aac5", "sender"));
    }
}
