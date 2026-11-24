'use strict';

const js = require('@eslint/js');

module.exports = [
  { ignores: ['node_modules/**'] },
  js.configs.recommended,
  {
    files: ['**/*.cjs'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'commonjs',
      globals: Object.fromEntries(
        [
          'Buffer',
          'URL',
          'URLSearchParams',
          'performance',
          'process',
          'console',
          'setTimeout',
          'clearTimeout',
          'setInterval',
          'clearInterval',
          'structuredClone',
          '__dirname',
          '__filename',
          'module',
          'exports',
          'require',
        ].map((name) => [name, 'readonly']),
      ),
    },
    rules: {
      'no-unused-vars': ['error', { args: 'none', caughtErrors: 'none', ignoreRestSiblings: true }],
    },
  },
];
