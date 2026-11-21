'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { ts, EXTENSIONS, slash } = require('../common/ast.cjs');

const MAX_FILE_BYTES = 5 * 1024 * 1024;
const IGNORED = new Set([
  '.git',
  'node_modules',
  'vendor',
  'dist',
  'build',
  'coverage',
  '.next',
  'target',
  'uploads',
  'public',
  'test',
  'tests',
  '__tests__',
  '__mocks__',
  '.venv',
  'venv',
  '__pycache__',
  'bin',
  'obj',
  'out',
  'wwwroot',
]);

/**
 * Inventory the whole root before creating any AST. This keeps aggregate source
 * admission independent of per-file parser amplification. Reads are bounded too:
 * a source that grows between stat and read cannot bypass the byte limits.
 */
function discoverSourceFiles({ root, config, diagnostics, resource }) {
  const files = new Map();
  const admitted = [];
  const excluded = new Set([...IGNORED, ...(config.excludeDirectories || [])]);
  let admittedBytes = 0;
  const relative = (absolute) => slash(path.relative(root, absolute));
  const oversized = (absolute) =>
    diagnostics.push({
      file: relative(absolute),
      line: 1,
      code: 'FILE_TOO_LARGE',
      message: 'Source file exceeds the 5 MiB parser limit.',
    });

  // An explicit stack avoids recursion depth growth in unusually deep directories.
  const directories = [root];
  while (directories.length) {
    const directory = directories.pop();
    const entries = [];
    const handle = fs.opendirSync(directory);
    
    try {
      let entry;
      while ((entry = handle.readSync()) !== null) {
        resource.directoryEntry();
        entries.push(entry);
      }
    } finally {
      handle.closeSync();
    }
    
    entries.sort((a, b) => a.name.localeCompare(b.name, 'en'));
    
    // Match the original lexicographic depth-first order without retaining ASTs.
    const pending = [];
    for (const entry of entries) {
      resource.step();
      const absolute = path.join(directory, entry.name);
      if (entry.isSymbolicLink()) {
        diagnostics.push({
          file: relative(absolute),
          line: 1,
          code: 'SYMLINK_SKIPPED',
          message: 'Symbolic links are not followed.',
        });
        continue;
      }

      if (entry.isDirectory()) {
        if (!excluded.has(entry.name) && !entry.name.startsWith('.')) pending.push(absolute);
        continue;
      }

      if (
        !EXTENSIONS.includes(path.extname(entry.name)) ||
        /\.(?:test|spec|d)\.[cm]?[jt]sx?$/.test(entry.name) ||
        entry.name.startsWith('.')
      )
        continue;
      
      const size = fs.statSync(absolute).size;
      if (size > MAX_FILE_BYTES) {
        oversized(absolute);
        continue;
      }

      if (admitted.length >= resource.limits.maxFiles) resource.fail('SOURCE_FILE_LIMIT');
      if (size > resource.limits.maxSourceBytes - admittedBytes) resource.fail('SOURCE_BYTE_LIMIT');
      admittedBytes += size;
      admitted.push({ absolute, size });
    }

    for (let i = pending.length - 1; i >= 0; i--) directories.push(pending[i]);
  }

  // The previous recursive traversal orders directories and files together, not
  // files before subdirectories. Sort by path segments to retain that order.
  admitted.sort((a, b) => compareSourcePaths(relative(a.absolute), relative(b.absolute)));

  for (const item of admitted) {
    const remaining = resource.limits.maxSourceBytes - resource.usage.sourceBytes;
    const maximum = Math.min(MAX_FILE_BYTES, remaining);
    const buffer = readBounded(item.absolute, maximum);
    if (buffer === null) {
      if (maximum < MAX_FILE_BYTES) resource.fail('SOURCE_BYTE_LIMIT');
      oversized(item.absolute);
      continue;
    }

    resource.sourceBytes(buffer.length);
    const extension = path.extname(item.absolute);
    const scriptKind =
      extension === '.tsx'
        ? ts.ScriptKind.TSX
        : ['.ts', '.mts', '.cts'].includes(extension)
          ? ts.ScriptKind.TS
          : ts.ScriptKind.JSX;
    
    const sf = ts.createSourceFile(
      item.absolute,
      buffer.toString('utf8'),
      ts.ScriptTarget.Latest,
      true,
      scriptKind,
    );

    // Check immediately after parsing, before retaining this AST or any binding
    // graph. Parser allocation itself remains isolated by the bridge's heap cap.
    const nodes = [sf];
    while (nodes.length) {
      const node = nodes.pop();
      resource.astNode();
      ts.forEachChild(node, (child) => {
        nodes.push(child);
      });
      // forEachChild omits attached JSDoc trees, which contracts may inspect.
      for (const documentation of node.jsDoc || []) nodes.push(documentation);
    }

    resource.file();
    const file = {
      absolute: item.absolute,
      rel: relative(item.absolute),
      sf,
      exports: new Map(),
      bindings: [],
      assignments: [],
      calls: [],
      bad: sf.parseDiagnostics.length > 0,
    };

    files.set(item.absolute, file);
    for (const issue of sf.parseDiagnostics)
      diagnostics.push({
        file: file.rel,
        line: sf.getLineAndCharacterOfPosition(issue.start || 0).line + 1,
        code: `PARSE_${issue.code}`,
        message:
          'Parser reported invalid or unsupported syntax; this file is excluded from extraction.',
      });
  }

  return files;
}

function compareSourcePaths(left, right) {
  const a = left.split('/'),
    b = right.split('/');
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    const difference = a[i].localeCompare(b[i], 'en');
    if (difference) return difference;
  }
  
  return a.length - b.length;
}

function readBounded(filename, maximum) {
  const fd = fs.openSync(filename, 'r');
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const chunk = Buffer.allocUnsafe(Math.min(64 * 1024, maximum - length + 1));
      const count = fs.readSync(fd, chunk, 0, chunk.length, null);
      if (count === 0) return Buffer.concat(chunks, length);
      length += count;
      if (length > maximum) return null;
      chunks.push(chunk.subarray(0, count));
    }
  } finally {
    fs.closeSync(fd);
  }
}

module.exports = { discoverSourceFiles, MAX_FILE_BYTES };
