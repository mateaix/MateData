CREATE TABLE IF NOT EXISTS md_document (
    namespace VARCHAR(64) NOT NULL,
    id VARCHAR(128) NOT NULL,
    payload CLOB NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY(namespace, id)
);
CREATE INDEX IF NOT EXISTS idx_document_recent ON md_document(namespace, updated_at DESC, id);
