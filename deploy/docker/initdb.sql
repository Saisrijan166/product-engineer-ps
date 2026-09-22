-- One PostgreSQL container, two databases.
--
-- The services own separate schemas by design (ARCHITECTURE.md 3.1), and that separation has to
-- be real even locally: each runs its own Flyway migrations against its own
-- flyway_schema_history, so a shared database would have them fighting over it. One container
-- keeps the reviewer's setup to a single command; two databases keep the boundary honest.
CREATE DATABASE webhook_ingest;
CREATE DATABASE webhook_delivery;
