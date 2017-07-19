SELECT DISTINCT constraint_name, table_name FROM information_schema.table_constraints WHERE table_name='am_app_key_domain_mapping' AND constraint_type='PRIMARY KEY'
ALTER TABLE AM_APP_KEY_DOMAIN_MAPPING DROP CONSTRAINT "<temp_key_name>";
