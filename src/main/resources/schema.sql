-- Both tables have the same clustered primary-key index.
-- The only schema difference is the secondary index on email.
CREATE TABLE IF NOT EXISTS Usertable1 (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    age INT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS Usertable2 (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    age INT NOT NULL,
    INDEX idx_usertable2_email (email)
) ENGINE=InnoDB;
