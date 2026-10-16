-- Position to role mapping.
ALTER TABLE positions ADD COLUMN role VARCHAR(30) NOT NULL DEFAULT 'EMPLOYEE';
