import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.*;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class XMLTest {
    @Test
    public void testBuildString() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> data = Map.of(
                "a", Map.of(
                        "b", Map.of(
                                "c", "d",
                                "e", "f"),
                        "g", "h"));
        new XmlPatcher().buildString(sb, data, ">");
        System.out.println(sb.toString());
        Assert.assertEquals(
                "\n" +
                        "><a>\n" +
                        ">   <b>\n" +
                        ">      <c>d</c>\n" +
                        ">      <e>f</e>\n" +
                        ">   </b>\n" +
                        ">   <g>h</g>\n" +
                        "></a>\n" +
                        "", sb.toString());

    }

    @Test
    public void diff() {
        final String text1 = "\n" +
                "><z>\n" +
                ">   <b>\n" +
                ">      <c>d</c>\n" +
                ">      <e>f</e>\n" +
                ">   </b>\n" +
                ">   <g>h</g>\n" +
                "></z>\n" +
                "";
        final String text2 = "\n" +
                "><a>\n" +
                ">   <b>\n" +
                ">      <c>d</c>\n" +
                ">      <e>f</e>\n" +
                ">   </b>\n" +
                ">   <g>h</g>\n" +
                "></a>\n" +
                "";
        new XmlPatcher().printColorDiff(text1, text2);
    }

    @Ignore
    @Test
    public void testDom() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = null;
        try {
            db = dbf.newDocumentBuilder();
            Document doc = db.parse(new File("/home/jdanek/Downloads/AMQ7/7.1.0/cr2.2/amq-broker-7.1.0/i0/etc/broker.xml"));

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            transformer.transform(new DOMSource(doc),
                    new StreamResult(new OutputStreamWriter(System.out, "UTF-8")));
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        }
    }

    @Ignore
    @Test
    public void stxblahTest() {
        Stream<XMLEvent> s = readxml();

        XMLOutputFactory w = XMLOutputFactory.newFactory();
        File out = new File("/tmp/out.xml");
        try (FileOutputStream os = new FileOutputStream(out)) {
            XMLEventWriter writer = w.createXMLEventWriter(os);
            s.forEach((e) -> {
                        System.out.println(e);

                        try {
                            writer.add(e);
                        } catch (XMLStreamException e1) {
                            e1.printStackTrace();
                        }
                    }
            );
            writer.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
    }

    Stream<XMLEvent> readxml() {
        List<XMLEvent> l = new ArrayList<>();

        File doc = new File("/home/jdanek/Downloads/AMQ7/7.1.0/cr2.2/amq-broker-7.1.0/i0/etc/broker.xml");
        XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
        try (InputStream is = new FileInputStream(doc)) {
            XMLEventReader xmlReader = xmlInputFactory.createXMLEventReader(is);
            while (xmlReader.hasNext()) {
                l.add(xmlReader.nextEvent());
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
        return l.stream();
    }

    @Test
    public void testANTLT() throws Exception {
        Path doc = Paths.get("/home/jdanek/Downloads/AMQ7/7.1.0/cr2.2/amq-broker-7.1.0/i0/etc/broker.xml");
        final XmlPatcher xmlPatcher = new XmlPatcher();
        String patched = xmlPatcher.patch(doc, Collections.emptyMap());
        String original = new String(Files.readAllBytes(doc));
        Assert.assertEquals(original, patched);

        patched = xmlPatcher.patch(doc,
                Map.of("configuration", Map.of("fuckdat", "ee")));
        xmlPatcher.printColorDiff(original, patched);

        patched = xmlPatcher.patch(doc,
                Map.of("configuration",
                        Map.of("core",
                                Map.of("address-settings",
                                        Map.of("address-setting",
                                                Map.of("slow-consumer-threshold", "1",
                                                        "slow-consumer-policy", "KILL",
                                                        "slow-consumer-check-period", "5"))))));
        xmlPatcher.printColorDiff(original, patched);
    }
}


