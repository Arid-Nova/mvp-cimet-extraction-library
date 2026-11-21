package edu.university.ecs.lab.common.parsers.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Protects the facts protocol while language visitors are maintained independently. */
class PolyglotFactExtractorRegressionTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void languageVisitorsPreservePreSplitFactsAcrossRepeatedMixedLanguageCalls() throws IOException {
    JsonNode expected = mapper.readTree(resource("expected-facts.json"));
    PolyglotExtractor extractor = new PolyglotExtractor();
    for (String filename :
        List.of("sample.js", "sample.py", "sample.go", "sample.cs", "sample.js", "sample.py")) {
      PolyglotExtractor.Language language =
          PolyglotExtractor.Language.forFile(filename).orElseThrow();
      PolyglotExtractor.Result result = extractor.extract(language, filename, resource(filename));
      assertEquals(0, result.syntaxErrors(), filename);
      assertEquals(filename, result.facts().getPath());
      assertEquals(
          expected.get(filename), mapper.valueToTree(result.facts().getDimensions()), filename);
      assertEquals(
          PolyglotExtractor.DIMENSIONS, List.copyOf(result.facts().getDimensions().keySet()));
    }
  }

  @Test
  void facadePreservesBomHandlingAndSyntaxErrorReporting() throws IOException {
    byte[] source = resource("sample.py");
    byte[] bomSource = new byte[source.length + 3];
    bomSource[0] = (byte) 0xef;
    bomSource[1] = (byte) 0xbb;
    bomSource[2] = (byte) 0xbf;
    System.arraycopy(source, 0, bomSource, 3, source.length);
    PolyglotExtractor extractor = new PolyglotExtractor();
    assertEquals(
        extractor
            .extract(PolyglotExtractor.Language.PYTHON, "sample.py", source)
            .facts()
            .getDimensions(),
        extractor
            .extract(PolyglotExtractor.Language.PYTHON, "sample.py", bomSource)
            .facts()
            .getDimensions());
    PolyglotExtractor.Result malformed =
        extractor.extract(
            PolyglotExtractor.Language.PYTHON,
            "broken.py",
            "def broken(\n".getBytes(StandardCharsets.UTF_8));
    assertTrue(malformed.syntaxErrors() > 0);
  }

  private byte[] resource(String filename) throws IOException {
    try (var stream = getClass().getResourceAsStream("/polyglot-facts/" + filename)) {
      assertNotNull(stream, filename);
      return stream.readAllBytes();
    }
  }
}
