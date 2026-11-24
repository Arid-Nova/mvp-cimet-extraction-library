'use strict';
const fs = require('node:fs');
const { AnalysisResourceLimitError } = require('./source/resources.cjs');

/** Parse only trusted adapter arguments/config data; sanitize errors instead of echoing target text. */
function runCli(analyze, args = process.argv.slice(2)) {
  try {
    const options = {};
    for (let i = 0; i < args.length; i += 2) {
      if (
        !['--root', '--kind', '--name', '--config'].includes(args[i]) ||
        !args[i + 1] ||
        options[args[i].substring(2)] !== undefined
      )
        throw new Error(
          'Usage: analyzer.cjs --root ABSOLUTE --kind backend|frontend --name NAME [--config JSON_FILE]',
        );
      options[args[i].substring(2)] = args[i + 1];
    }
    if (options.config) {
      if (fs.statSync(options.config).size > 64 * 1024)
        throw new Error('Adapter configuration exceeds 64 KiB.');
      options.config = JSON.parse(fs.readFileSync(options.config, 'utf8'));
    }
    process.stdout.write(JSON.stringify(analyze(options)) + '\n');
  } catch (error) {
    // Error messages never include target source or literal configuration values.
    const resourceCode = error instanceof AnalysisResourceLimitError ? ` [${error.code}]` : '';
    process.stderr.write(
      `JavaScript analysis failed${resourceCode}: ${error instanceof Error && /^(Provide|The analysis|Source file|Invalid wrapper|Usage:|Adapter configuration)/.test(error.message) ? error.message : 'invalid input, filesystem access, or parser failure.'}\n`,
    );
    process.exitCode = 1;
  }
}

module.exports = { runCli };
