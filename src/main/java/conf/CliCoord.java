package conf;

import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;

public class CliCoord implements Comparable<CliCoord> {
    String name;
    LinkedHashSet<String> tags;
    String kind;

    CliCoord(String name, LinkedHashSet<String> tags, String kind) {
        this.name = name;
        this.tags = tags;
        this.kind = kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CliCoord cliCoord = (CliCoord) o;
        return Objects.equals(name, cliCoord.name) &&
                Objects.equals(tags, cliCoord.tags) &&
                Objects.equals(kind, cliCoord.kind);
    }

    @Override
    public int hashCode() {

        return Objects.hash(name, tags, kind);
    }

    @Override
    public int compareTo(@NotNull CliCoord o) {

        if (!name.equals(o.name)) {
            return name.compareTo(o.name);
        }
        if (!tags.equals(o.tags)) {
            if (tags.size() != o.tags.size()) {
                return Integer.compare(tags.size(), o.tags.size());
            }
            Iterator<String> it = tags.iterator();
            Iterator<String> jt = o.tags.iterator();
            while (it.hasNext()) {
                String i = it.next();
                String j = jt.next();
                if (!i.equals(j)) {
                    return i.compareTo(j);
                }
            }
            throw new AssertionError("The execution may never get here");
        }
        return kind.compareTo(o.kind);
    }

    @Override
    public String toString() {
        return "CliCoord{" +
                "name='" + name + '\'' +
                ", tags=" + tags +
                ", kind='" + kind + '\'' +
                '}';
    }
}
