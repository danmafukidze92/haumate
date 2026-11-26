-- Run once on the PostgreSQL server (pgadmin.nexigent-labs.co.zw) as a superuser, e.g. in pgAdmin
-- or: docker exec -it call_center_demo psql -U postgres -f /tmp/create-db.sql
-- Safe to re-run: skips the user/database if they already exist.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'haulmate') THEN
    CREATE ROLE haulmate LOGIN PASSWORD 'haulmate@2026';
  END IF;
END $$;

SELECT 'CREATE DATABASE haulmate OWNER haulmate'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'haulmate')\gexec

\c haulmate
CREATE SCHEMA IF NOT EXISTS haulmate AUTHORIZATION haulmate;
GRANT ALL ON SCHEMA haulmate TO haulmate;
-- Tables are created by Flyway when the app first starts.
