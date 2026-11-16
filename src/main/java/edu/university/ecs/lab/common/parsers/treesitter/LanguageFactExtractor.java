package edu.university.ecs.lab.common.parsers.treesitter;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;

/**
 * One language's stateless traversal of a materialized syntax tree.
 *
 * <p>Call attribution uses &lt;module&gt; outside functions and &lt;anonymous&gt; for unnamed
 * functions. Named methods are qualified by their nearest owning declaration.
 */
interface LanguageFactExtractor {
  void extract(SNode root, FactOutput output);
}
