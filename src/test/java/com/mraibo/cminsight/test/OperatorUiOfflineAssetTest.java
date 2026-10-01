package com.mraibo.cminsight.test;

import com.mraibo.cminsight.config.AppConfig;
import com.mraibo.cminsight.config.SecretResolver;
import com.mraibo.cminsight.config.WebAuthSettings;
import com.mraibo.cminsight.web.Router;
import com.mraibo.cminsight.web.WebServer;
import com.mraibo.cminsight.web.http.RequestContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Goal 04 sections 8, 9 and 12: the bundled operator console is fully offline and renders server-controlled
 * values as TEXT.
 *
 * <h2>Why this scans the real asset bytes, and why that is the honest maximum</h2>
 *
 * <p>There is no browser and no JavaScript engine in this build - plain {@code javac}/{@code java}, no
 * download, no DOM harness - so "the UI renders a hostile string as text" cannot be observed by executing
 * the page. What CAN be observed is the code that does the rendering, and the two rules that make it true:
 * a value reaches the document through {@code textContent}/{@code createTextNode} rather than through an
 * HTML parse, and no asset reaches an external origin at runtime. Both are properties of the asset bytes,
 * and the bytes the server returns are asserted to be the committed bytes, so the scan is performed on
 * exactly what an operator's browser receives.
 *
 * <h2>The controls that make each scan discriminating</h2>
 *
 * <p>A scanner that can only ever say "clean" proves nothing, so every scanner here is run twice: over the
 * committed asset (which must be clean) and over a copy mutated with the exact defect the scan exists to
 * catch - an injected {@code https://cdn...} reference, a text assignment turned into an HTML assignment.
 * The mutated copy MUST be reported. A rule that cannot fail is a comment, not a control.
 */
public class OperatorUiOfflineAssetTest {

    private static final String USER = "ui-ops";

    private static final String PASSWORD = "Operator-Ui-Asset-42";

    /** The three bundled assets, as {@code WebServer} serves them below its {@code /web} prefix. */
    private static final List<String> ASSETS = List.of("index.html", "app.css", "app.js");

    // ------------------------------------------------------------------ offline

    /** No bundled asset may reference an external origin, and the server must serve the committed bytes. */
    public void everyStaticAssetIsLocalAndCarriesNoExternalRuntimeUrl() throws Exception {
        for (String asset : ASSETS) {
            String text = assetText(asset);
            Assert.assertFalse(text.isBlank(), asset + " must not be an empty asset");

            List<String> findings = externalReferenceFindings(asset, text);
            Assert.assertTrue(findings.isEmpty(),
                    "every static asset must be fully offline (Goal 04 section 8: no CDN, external fonts,"
                            + " analytics scripts or runtime Internet access), but " + asset + " references an"
                            + " external origin: " + findings);
        }
    }

    /**
     * The control for {@link #everyStaticAssetIsLocalAndCarriesNoExternalRuntimeUrl()}: an injected CDN
     * reference must be reported.
     *
     * <p>Without this, a scanner whose patterns never match anything would pass the suite above forever.
     */
    public void theExternalReferenceScannerReportsAnInjectedCdnReference() {
        String clean = "var a = 1; // a purely local asset\n";
        Assert.assertTrue(externalReferenceFindings("clean.js", clean).isEmpty(),
                "the control must start from a source the scanner accepts, or it proves nothing about the"
                        + " scanner");

        List<String> hostile = List.of(
                "var s = document.createElement('script'); s.src = 'https://cdn.example.test/lib.js';\n",
                "a.style.background = 'url(https://cdn.example.test/x.png)';\n",
                "//# sourceMappingURL=http://maps.example.test/app.js.map\n",
                "im.src = '//tracker.example.test/p.gif';\n",
                "<link rel=\"stylesheet\" href=\"https://fonts.example.test/x.css\">\n");
        for (String injected : hostile) {
            List<String> findings = externalReferenceFindings("mutated.js", clean + injected);
            Assert.assertFalse(findings.isEmpty(),
                    "the external-reference scan must FAIL for an injected external URL, but accepted: "
                            + injected.trim());
        }
    }

    // ------------------------------------------------------------------ text, not markup

    /**
     * Every dynamic value reaches the document as TEXT: no server-controlled string is parsed as HTML.
     *
     * <p>Goal 04 section 9 makes this the rule for the whole console, and it decides whether an ItemType
     * named {@code <img src=x onerror=...>} is displayed or executed.
     */
    public void theConsoleRendersDynamicValuesAsTextNotAsMarkup() throws Exception {
        String script = assetText("app.js");

        List<String> findings = markupInjectionFindings("app.js", script);
        Assert.assertTrue(findings.isEmpty(),
                "Goal 04 section 9: dynamic values must be set with textContent/createTextNode, never parsed"
                        + " as HTML. Found a markup assignment fed by a non-literal value: " + findings);

        Assert.assertTrue(script.contains("textContent") || script.contains("createTextNode"),
                "the console must render dynamic values through textContent/createTextNode; an asset that"
                        + " uses neither has no mechanism for Goal 04 section 9 at all");
    }

    /** The control for {@link #theConsoleRendersDynamicValuesAsTextNotAsMarkup()}. */
    public void theMarkupInjectionScannerReportsATextContentAssignmentTurnedIntoInnerHtml() {
        String clean = "function setText(node, value) {\n  node.textContent = value;\n}\n";
        Assert.assertTrue(markupInjectionFindings("clean.js", clean).isEmpty(),
                "the control must start from a source the scanner accepts, or it proves nothing");

        String mutated = clean.replace("node.textContent = value;", "node.innerHTML = value;");
        Assert.assertFalse(mutated.equals(clean), "the mutation must actually change the source");
        List<String> findings = markupInjectionFindings("mutated.js", mutated);
        Assert.assertFalse(findings.isEmpty(),
                "the markup-injection scan must FAIL when a text assignment becomes an HTML assignment");

        String concatenation = clean + "cell.innerHTML = '<b>' + name + '</b>';\n";
        Assert.assertFalse(markupInjectionFindings("mutated.js", concatenation).isEmpty(),
                "and it must fail for an HTML assignment built by concatenation, which is the same defect in"
                        + " the shape a UI actually writes it");
    }

    // ------------------------------------------------------------------ the served bytes

    /**
     * The route that serves the console returns the committed asset bytes, to an authenticated caller only.
     *
     * <p>This ties the scans above to what a browser really receives: a scan of a file the server does not
     * serve would be a statement about the wrong bytes.
     */
    public void theConsoleIsServedToAuthenticatedCallersFromTheCommittedBytes() throws Exception {
        Router router = new Router();
        WebServer server = new WebServer(serverConfig(), authSettings(), router);
        server.start();
        try {
            FakeTransport anonymous = FakeTransport.get(WebServer.STATIC_PREFIX + "/app.js");
            router.handle(new RequestContext(anonymous, "ui-asset-anon"));
            Assert.assertEquals(401, anonymous.status(),
                    "the bundled console must not be readable without credentials, but an anonymous request"
                            + " answered " + anonymous.status() + ": " + anonymous.bodyText());

            for (String asset : ASSETS) {
                FakeTransport transport = FakeTransport.get(WebServer.STATIC_PREFIX + "/" + asset)
                        .withHeader("Authorization", TestSupport.basic(USER, PASSWORD));
                router.handle(new RequestContext(transport, "ui-asset"));
                Assert.assertEquals(200, transport.status(),
                        WebServer.STATIC_PREFIX + "/" + asset + " must be served, but answered "
                                + transport.status() + ": " + transport.bodyText());
                Assert.assertEquals(assetText(asset), transport.bodyText(),
                        "the served bytes must be the committed asset: a scan of a file the server does not"
                                + " serve would be evidence about the wrong artifact");
            }
        } finally {
            server.close();
        }
    }

    // ------------------------------------------------------------------ scanners

    /** Every occurrence of an external origin in one asset, as a line-numbered finding. */
    private static List<String> externalReferenceFindings(String name, String text) {
        List<String> findings = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (ABSOLUTE_URL.matcher(line).find()) {
                findings.add(name + ":" + (index + 1) + " absolute URL in: " + trimmed);
            }
            if (PROTOCOL_RELATIVE_URL.matcher(line).find()) {
                findings.add(name + ":" + (index + 1) + " protocol-relative URL in: " + trimmed);
            }
            if (trimmed.contains("integrity=") || trimmed.contains("crossorigin=")) {
                findings.add(name + ":" + (index + 1) + " an SRI/CORS attribute implies an external asset: "
                        + trimmed);
            }
        }
        return findings;
    }

    /**
     * Every markup assignment fed by something other than a literal.
     *
     * <p>The rule is deliberately narrower than "no innerHTML anywhere": assigning an empty literal to clear
     * a node is not an injection, and failing it would force a weaker test later. What is forbidden is an
     * HTML-parse sink whose argument is a value, a concatenation or a template.
     */
    private static List<String> markupInjectionFindings(String name, String text) {
        List<String> findings = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;

            Matcher write = DOCUMENT_WRITE.matcher(code);
            while (write.find()) {
                String argument = code.substring(write.end()).trim();
                if (!isLiteralOnly(stripClosingParen(argument))) {
                    findings.add(name + ":" + (index + 1) + " document.write fed by a non-literal value: "
                            + line.trim());
                }
            }

            Matcher sink = MARKUP_SINK.matcher(code);
            while (sink.find()) {
                String rightHandSide = code.substring(sink.end()).trim();
                if (!isLiteralOnly(rightHandSide)) {
                    findings.add(name + ":" + (index + 1) + " " + sink.group(1)
                            + " fed by a non-literal value: " + line.trim());
                }
            }
        }
        return findings;
    }

    /** Removes one trailing {@code )} so a call argument can be judged by the same literal rule. */
    private static String stripClosingParen(String argument) {
        return argument.endsWith(")") ? argument.substring(0, argument.length() - 1).trim() : argument;
    }

    /** True when the assigned value is a literal (optionally an empty one), so nothing is injected. */
    private static boolean isLiteralOnly(String rightHandSide) {
        if (rightHandSide.isEmpty()) {
            return true; // a getter-style sink, for example a read of node.outerHTML
        }
        char first = rightHandSide.charAt(0);
        if (first != '\'' && first != '"' && first != '`') {
            return false;
        }
        int end = rightHandSide.indexOf(first, 1);
        if (end < 0) {
            return false; // unterminated: the conservative answer is "not provably literal"
        }
        String literal = rightHandSide.substring(1, end);
        if (literal.contains("${")) {
            return false; // a template literal with an interpolated value is not a literal
        }
        String rest = rightHandSide.substring(end + 1).trim();
        // Only a statement terminator (or the closing call paren) may follow: `'x' + value` is a mistake.
        return rest.isEmpty() || rest.startsWith(";") || rest.startsWith(")") || rest.startsWith(",");
    }

    // ------------------------------------------------------------------ harness

    /** The committed asset text, from the class path the server itself reads. */
    private static String assetText(String asset) throws IOException {
        String resource = WebServer.STATIC_PREFIX + "/" + asset;
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path fromWorkingDirectory = Path.of("src", "main", "resources", "web", asset);
        if (Files.isRegularFile(fromWorkingDirectory)) {
            return Files.readString(fromWorkingDirectory, StandardCharsets.UTF_8);
        }
        throw new AssertionError("the bundled asset " + resource + " is neither on the class path nor at "
                + fromWorkingDirectory.toAbsolutePath() + "; the console cannot be validated and an absent"
                + " asset must never read as a pass");
    }

    private static AppConfig serverConfig() {
        Properties properties = new Properties();
        properties.setProperty("web.bind", "127.0.0.1");
        properties.setProperty("web.port", "0");
        properties.setProperty("web.auth.user", USER);
        properties.setProperty("web.auth.password", PASSWORD);
        properties.setProperty("web.threads", "2");
        return AppConfig.fromProperties(properties);
    }

    private static WebAuthSettings authSettings() {
        return WebAuthSettings.resolve(serverConfig(), new SecretResolver(Map.of(), null));
    }

    /** An absolute {@code http(s)} URL anywhere in a line, including inside a comment or a string. */
    private static final Pattern ABSOLUTE_URL = Pattern.compile("(?i)https?://");

    /** A protocol-relative URL in an attribute or CSS value: {@code src="//host/..."}. */
    private static final Pattern PROTOCOL_RELATIVE_URL =
            Pattern.compile("(?i)(src|href|action|url)\\s*(\\(|=|:)\\s*[\"']?\\s*//");

    /** An HTML-parse sink whose argument must be a literal. {@code insertAdjacentText} is NOT one: it inserts text. */
    private static final Pattern MARKUP_SINK = Pattern.compile(
            "\\.(innerHTML|outerHTML|insertAdjacentHTML)\\s*=|"
                    + "\\.(innerHTML|outerHTML|insertAdjacentHTML)\\s*\\+=");

    /** {@code document.write(...)}, which parses its argument as HTML. */
    private static final Pattern DOCUMENT_WRITE = Pattern.compile("document\\.write(?:ln)?\\s*\\(");
}

