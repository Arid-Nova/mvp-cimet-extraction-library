#!/usr/bin/env node
'use strict';
// Stable public entry point. Only the pinned compiler is loaded; target code is never executed.
const { analyze } = require('./lib/analysis.cjs');
if (require.main === module) require('./lib/cli.cjs').runCli(analyze);
module.exports = { analyze };
