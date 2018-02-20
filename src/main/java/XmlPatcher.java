import com.google.common.escape.Escaper;
import com.google.common.xml.XmlEscapers;
import name.fraser.neil.plaintext.diff_match_patch;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.fusesource.jansi.Ansi.ansi;

public class XmlPatcher {
    public String patch(Path doc, Map<String, Object> patch) {
        try {
            CharStream is = CharStreams.fromPath(doc);
            return patch(is, patch);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        throw new RuntimeException();
    }

    public String patch(CharStream is, Map<String, Object> patch) {
        XMLLexer lexer = new XMLLexer(is);
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        XMLParser parser = new XMLParser(tokens);
        ParseTree tree = parser.document();

        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokens);

        tree.accept(new MyVisitor(rewriter, patch));
        return (rewriter.getText());
    }

    void printColorDiff(String text1, String text2) {
        diff_match_patch dmp = new diff_match_patch();
        LinkedList<diff_match_patch.Diff> diff = dmp.diff_main(text1, text2);
        dmp.diff_cleanupSemantic(diff);
        System.setProperty("jansi.passthrough", "true");
        AnsiConsole.systemInstall();
        for (diff_match_patch.Diff d : diff) {
            switch (d.operation) {
                case EQUAL:
                    System.out.println(abbreviate(d.text));
                    break;
                case DELETE:
                    System.out.print(ansi().fg(Ansi.Color.RED).a(d.text).reset());
                    break;
                case INSERT:
                    System.out.print(ansi().fg(Ansi.Color.GREEN).a(d.text).reset());
                    break;
            }
        }
        AnsiConsole.systemUninstall();
    }

    private String abbreviate(String s) {
        List<String> abbreviated = new ArrayList<>();
        String[] lines = s.split("\n");
        boolean ellipsis = false;
        for (int i = 0; i < lines.length; i++) {
            if (i < 3 || i > lines.length - 4) {
                abbreviated.add(lines[i]);
            } else {
                if (!ellipsis) {
                    abbreviated.add("[...]");
                    ellipsis = true;
                }
            }
        }
        return String.join("\n", abbreviated);
    }

    private class MyVisitor extends XMLParserBaseVisitor implements XMLParserVisitor {
        private final TokenStreamRewriter rewriter;
        private final Map<String, Object> rewrite;
        private Object current;

        public MyVisitor(TokenStreamRewriter rewriter, Map<String, Object> rewrite) {
            this.rewriter = rewriter;
            this.rewrite = rewrite;
        }

        @Override
        public Object visitDocument(XMLParser.DocumentContext ctx) {
            current = rewrite;
            ctx.element().accept(this);
            return null;
        }

        @Override
        public Object visitElement(XMLParser.ElementContext ctx) {
            maybeReplaceValue(ctx);
            if (ctx.content() != null && current != null) {
                ctx.content().accept(this);
            }
            return null;
        }

        void maybeReplaceValue(XMLParser.ElementContext ctx) {
            if (current instanceof Map) {
                String name = ctx.Name().get(0).getText();
                current = ((Map) current).get(name);

                if (current == null) {
                    return;
                }
                if (current instanceof List) {
                    String tag = getTagFromElement(ctx);
                    List<List<String>> attributes = maybeGetAttributes(current);
                    int i;
                    for (i = 0; i < attributes.size(); i++) {
                        String t = formatTag(name, attributes.get(i));
                        if (t.equals(tag)) {
                            break;
                        }
                    }
                    if (i < attributes.size()) {
                        current = ((List) current).get(i);
                    }
                    return;
                } else if (current instanceof String) {
                    Token b = ctx.content().start;
                    Token e = ctx.content().stop;
                    rewriter.replace(b, e, current);
                    return;
                }
            }
        }

        @Override
        public Object visitContent(XMLParser.ContentContext ctx) {
            Object old = current;
            for (XMLParser.ElementContext e : ctx.element()) {
                e.accept(this);
                current = old;
            }
            maybeAddSubtree(ctx, ctx.element().stream().map((e) -> e.Name(0).getText()).collect(Collectors.toSet()));
            return null;
        }

        private void maybeAddSubtree(XMLParser.ContentContext ctx, Set<String> elements) {
            if (current instanceof Map) {
                String indent = computeIndent(ctx.start);
                // how many streams it cab take to sort an array, let me count the ways...
                for (String key : ((Map<String, ?>) current).keySet().stream().sorted().collect(Collectors.toList())) {
                    List<List<String>> AttrLists = maybeGetAttributes(((Map) current).get(key));
                    if (AttrLists.size() > 0) {
                        Set<String> tagElements = ctx.element().stream()
                                .map((e) -> getTagFromElement(e))
                                .collect(Collectors.toSet());
                        StringBuilder sb = new StringBuilder();
                        for (List<String> attrList : AttrLists) {
                            String tag = formatTag(key, attrList);
                            if (!tagElements.contains(tag)) {
                                buildString(sb, new HashMap<String, Object>() {{
                                    put(key, ((Map) current).get(key));
                                }}, indent);
                            }
                            if (sb.length() == 0) {
                                continue;
                            }
                            rewriter.insertAfter(ctx.start, sb.toString().trim());
                            rewriter.insertAfter(ctx.start, ctx.start.getText());
                        }
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    if (!elements.contains(key)) {
                        buildString(sb, new HashMap<String, Object>() {{
                            put(key, ((Map) current).get(key));
                        }}, indent);
                    }
                    if (sb.length() == 0) {
                        continue;
                    }
                    rewriter.insertAfter(ctx.start, sb.toString().trim());
                    rewriter.insertAfter(ctx.start, ctx.start.getText());
                }
            }
        }

        private String computeIndent(Token start) {
            String s = rewriter.getTokenStream().get(start.getTokenIndex()).getText();
            int suffixEnd = s.length();
            while (suffixEnd > 1 && s.charAt(suffixEnd - 1) != '\n') {
                suffixEnd--;
            }
            return s.substring(suffixEnd);
        }
    }

    //todo maybe use jdom or xom; indent block and no single top-level parent node -- xml fragments
    // or neko, xerxes
    void buildString(StringBuilder sb, Object o, String indent) {
        if (o instanceof List) {
            for (Map oi : (List<Map>) o) {
                buildString(sb, oi, indent);
            }
        } else if (o instanceof Map) {
            boolean first = true;
            List<String> keys = new ArrayList<>(((Map<String, Object>) o).keySet());
            keys.removeIf((k) -> k.startsWith("@"));  // attributes for the tag above
            keys.sort(String::compareTo);
            for (String key : keys) {
                // todo can I have more than one iteration here? maybe not
                Object value = ((Map) o).get(key);
                List<List<String>> maybeAttributes = maybeGetAttributes(value);
                if (maybeAttributes.size() == 0) {
                    // ensure we run the loop even without any
                    maybeAttributes.add(new ArrayList<>());
                }

                for (List<String> attributes : maybeAttributes) {
                    if (first) {
                        sb.append('\n');
                        first = false;
                    }
                    sb.append(indent);
                    sb.append("<").append(key);
                    if (attributes.size() > 0) {
                        sb.append(" ").append(String.join(" ", attributes));
                    }
                    sb.append(">");
                    buildString(sb, value, indent + "   ");
                    if (sb.charAt(sb.length() - 1) == '\n') {
                        sb.append(indent);
                    }
                    sb.append("</").append(key).append(">\n");
                }
            }
        } else if (o instanceof String) {
            String content = XmlEscapers.xmlContentEscaper().escape(((String) o));
            sb.append(content);
        }
    }

    private String stripQuotes(String string) {
        return string.substring(1, string.length() - 1);
    }

    // todo I am going to want object for this
    private String getTagFromElement(XMLParser.ElementContext ctx) {
        List<String> attributes = ctx.attribute().stream().map((a) -> {
            return a.Name() + "=\"" + stripQuotes(a.STRING().getText()) + "\"";
        }).sorted().collect(Collectors.toList());
        return "<" + ctx.Name(0) + " " + String.join(" ", attributes) + ">";
    }

    List<String> getTagsFromMap(Map<String, Object> map) {
        List<String> tags = new ArrayList<>();
        List<String> keys = map.keySet().stream().sorted().collect(Collectors.toList());
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Map || value instanceof String) {
                tags.add("<" + key + ">");
            } else {
                for (List<String> attributes : maybeGetAttributes(value)) {
                    tags.add(formatTag(key, attributes));
                }
            }
        }
        return tags;
    }

    private String formatTag(String name, List<String> attributes) {
        return "<" + name + " " + String.join(" ", attributes) + ">";
    }

    List<List<String>> maybeGetAttributes(Object value) {
        List<List<String>> result = new ArrayList<>();
        Escaper attributeEscaper = XmlEscapers.xmlAttributeEscaper();
        if (value instanceof List) {
            for (Map<String, Object> nestedMap : ((List<Map<String, Object>>) value)) {
                List<String> attributes = nestedMap.keySet().stream()
                        .filter((k) -> k.startsWith("@"))
                        .sorted()
                        .map((k) -> k.substring(1) + "=\"" + attributeEscaper.escape((String) nestedMap.get(k)) + "\"")
                        .collect(Collectors.toList());
                result.add(attributes);
            }
        }
        return result;
    }
}