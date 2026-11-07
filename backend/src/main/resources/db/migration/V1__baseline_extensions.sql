-- Stage 1 baseline: PostgreSQL extensions used across the platform.
-- On managed databases where the application role cannot create extensions, these must be created by an
-- administrator beforehand (see docs/deployment.md); IF NOT EXISTS makes the migration a no-op in that case.
CREATE EXTENSION IF NOT EXISTS vector;     -- pgvector: embeddings for semantic search and RAG
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- trigram similarity: fuzzy matching of authority names, dedup
CREATE EXTENSION IF NOT EXISTS unaccent;   -- accent-insensitive full-text search across EU languages
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive e-mail addresses
CREATE EXTENSION IF NOT EXISTS btree_gin;  -- composite GIN indexes (filters + full text)
