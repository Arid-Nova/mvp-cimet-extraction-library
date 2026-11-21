'use strict';
const { hash } = require('./common/ast.cjs');

/** Stable semantic identities deliberately exclude timestamps and source line movement. */
function finalizeRecords(records, category, name, kind) {
  const occurrences = new Map();
  for (const record of records) {
    const identity = JSON.stringify([
      name,
      kind,
      category,
      record.file,
      record.caller,
      record.method,
      record.normalizedPath ?? record.path,
    ]);
    const ordinal = (occurrences.get(identity) || 0) + 1;
    occurrences.set(identity, ordinal);
    record.id = `${category}-${hash(`${identity}:${ordinal}`).slice(0, 24)}`;
    record.contractFingerprint = hash(
      JSON.stringify([
        record.method,
        record.normalizedPath ?? record.path,
        record.target || 'endpoint',
      ]),
    );
    record.fingerprint = hash(`${record.contractFingerprint}:${record.bodyFingerprint}`);
  }
  
  return records.sort(
    (a, b) =>
      a.file.localeCompare(b.file, 'en') || a.line - b.line || a.id.localeCompare(b.id, 'en'),
  );
}

function sourceCoverage(diagnostics) {
  const sourceExclusions = diagnostics.filter(
    (d) => d.code.startsWith('PARSE_') || ['FILE_TOO_LARGE', 'SYMLINK_SKIPPED'].includes(d.code),
  );
  const counts = {
    sourceExcludedPaths: new Set(sourceExclusions.map((d) => d.file)).size,
    sourceParseErrorFiles: new Set(
      sourceExclusions.filter((d) => d.code.startsWith('PARSE_')).map((d) => d.file),
    ).size,
    sourceOversizedFiles: new Set(
      sourceExclusions.filter((d) => d.code === 'FILE_TOO_LARGE').map((d) => d.file),
    ).size,
    sourceSymlinkPaths: new Set(
      sourceExclusions.filter((d) => d.code === 'SYMLINK_SKIPPED').map((d) => d.file),
    ).size,
  };
  
  return {
    sourceExclusionCounts: counts,
    sourceInspectionComplete: counts.sourceExcludedPaths === 0,
  };
}

function orderedDiagnostics(diagnostics) {
  return [...new Map(diagnostics.map((d) => [JSON.stringify(d), d])).values()].sort(
    (a, b) =>
      a.file.localeCompare(b.file, 'en') || a.line - b.line || a.code.localeCompare(b.code, 'en'),
  );
}

module.exports = { finalizeRecords, sourceCoverage, orderedDiagnostics };
