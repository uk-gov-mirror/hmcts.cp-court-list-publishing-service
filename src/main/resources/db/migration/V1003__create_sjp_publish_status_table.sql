-- V1003__create_sjp_publish_status_table.sql
-- Tracks SJP publish status for async processing, dedup key reuse (court centre + list type + date)
-- and content-hash based dedup, matching the pattern already used by court_list_publish_status.

CREATE TABLE sjp_publish_status (
    sjp_list_id           UUID                     PRIMARY KEY,
    court_id_numeric      TEXT                     NOT NULL,
    list_type             TEXT                     NOT NULL,
    publish_date          DATE                     NOT NULL,
    publish_status        TEXT                     NOT NULL,
    payload_hash          TEXT,
    publish_error_message TEXT,
    last_updated          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX ux_sjp_publish_status_dedup_key
    ON sjp_publish_status (court_id_numeric, list_type, publish_date);
