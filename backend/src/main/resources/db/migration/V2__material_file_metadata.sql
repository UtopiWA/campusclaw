ALTER TABLE materials
    ADD COLUMN file_size_bytes BIGINT NULL AFTER stored_path;
