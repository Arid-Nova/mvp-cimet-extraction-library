'use strict';

// Admission budgets complement the Java bridge's process heap/time/output limits.
// They bound admitted source and retained evidence, not total process RSS.
const LIMITS = Object.freeze({
  maxFiles: { default: 20_000, maximum: 20_000 },
  maxDirectoryEntries: { default: 100_000, maximum: 1_000_000 },
  maxSourceBytes: { default: 16 * 1024 * 1024, maximum: 64 * 1024 * 1024 },
  maxAstNodes: { default: 500_000, maximum: 2_000_000 },
  maxWorkOperations: { default: 10_000_000, maximum: 100_000_000 },
  maxRecords: { default: 100_000, maximum: 1_000_000 },
});

const MESSAGES = Object.freeze({
  SOURCE_FILE_LIMIT: 'Source file count exceeds the configured limit.',
  SOURCE_ENTRY_LIMIT: 'The analysis exceeded its directory-entry limit.',
  SOURCE_BYTE_LIMIT: 'The analysis exceeded its aggregate source-byte limit.',
  AST_NODE_LIMIT: 'The analysis exceeded its retained AST-node limit.',
  ANALYSIS_WORK_LIMIT: 'The analysis exceeded its operation limit.',
  ANALYSIS_RECORD_LIMIT: 'The analysis exceeded its evidence-record limit.',
});

class AnalysisResourceLimitError extends Error {
  constructor(code) {
    super(MESSAGES[code]);
    this.name = 'AnalysisResourceLimitError';
    this.code = code;
  }
}

function createResourceBudget(config) {
  const limits = {};
  for (const [name, bounds] of Object.entries(LIMITS)) {
    const value = config[name] ?? bounds.default;
    if (!Number.isSafeInteger(value) || value < 1 || value > bounds.maximum) {
      throw new Error(`Adapter configuration has an invalid ${name} resource limit.`);
    }
    limits[name] = value;
  }

  const usage = {
    files: 0,
    directoryEntries: 0,
    sourceBytes: 0,
    astNodes: 0,
    workOperations: 0,
    records: 0,
  };

  function take(counter, amount, maximum, code) {
    if (amount > limits[maximum] - usage[counter]) throw new AnalysisResourceLimitError(code);
    usage[counter] += amount;
  }
  
  return {
    limits,
    usage,
    file() {
      take('files', 1, 'maxFiles', 'SOURCE_FILE_LIMIT');
    },
    directoryEntry() {
      take('directoryEntries', 1, 'maxDirectoryEntries', 'SOURCE_ENTRY_LIMIT');
    },
    sourceBytes(amount) {
      take('sourceBytes', amount, 'maxSourceBytes', 'SOURCE_BYTE_LIMIT');
    },
    astNode() {
      take('astNodes', 1, 'maxAstNodes', 'AST_NODE_LIMIT');
    },
    step(amount = 1) {
      take('workOperations', amount, 'maxWorkOperations', 'ANALYSIS_WORK_LIMIT');
    },
    record(amount = 1) {
      take('records', amount, 'maxRecords', 'ANALYSIS_RECORD_LIMIT');
    },
    fail(code) {
      throw new AnalysisResourceLimitError(code);
    },
  };
}

module.exports = { createResourceBudget, AnalysisResourceLimitError, LIMITS };
