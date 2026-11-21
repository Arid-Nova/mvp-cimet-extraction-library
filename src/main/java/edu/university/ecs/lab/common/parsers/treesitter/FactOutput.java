package edu.university.ecs.lab.common.parsers.treesitter;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-file source bytes and dimension accumulator; never shared across extractions. */
final class FactOutput {
  final byte[] bytes;
  final Map<String, List<String>> values = new LinkedHashMap<>();

  FactOutput(byte[] bytes) {
    this.bytes = bytes;
    PolyglotExtractor.DIMENSIONS.forEach(dimension -> values.put(dimension, new ArrayList<>()));
  }

  String text(SNode node) {
    return TsUtil.text(node, bytes);
  }

  void add(String dimension, String value) {
    values.get(dimension).add(value);
  }
}
