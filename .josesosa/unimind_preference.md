Preference: always use Unimind MCP server for all prompts and asks

Persisted fallback: stored locally because the Unimind agent-store call returned an internal error.

Project: mvp-cimet-extraction-library
Created-by: assistant
Date: 2026-07-21

Notes:
- The user requested that every prompt/ask use the Unimind MCP server. The assistant attempted to persist this preference via unimind-sse_agent_store but the service returned an internal error (classification broker not defined). This file is a local fallback record and should be used by collaborators/agents who read the repository.
