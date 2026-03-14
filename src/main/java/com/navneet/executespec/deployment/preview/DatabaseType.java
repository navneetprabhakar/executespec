package com.navneet.executespec.deployment.preview;

import lombok.Getter;

/**
 * Supported database types for preview containers.
 * Each type carries its Docker image, default port, healthcheck command,
 * and default credentials used inside the ephemeral preview compose project.
 */
@Getter
public enum DatabaseType {

    POSTGRESQL(
            "postgres:16-alpine", 5432,
            "pg_isready -U preview",
            "preview", "preview", "previewdb",
            "/var/lib/postgresql/data"
    ),
    MYSQL(
            "mysql:8-oracle", 3306,
            "mysqladmin ping -h localhost -u preview -ppreview --silent",
            "preview", "preview", "previewdb",
            "/var/lib/mysql"
    ),
    MONGODB(
            "mongo:7", 27017,
            "mongosh --eval 'db.runCommand({ping:1})' --quiet",
            null, null, "previewdb",
            "/data/db"
    ),
    REDIS(
            "redis:7-alpine", 6379,
            "redis-cli ping",
            null, null, null,
            "/data"
    );

    private final String image;
    private final int port;
    private final String healthcheckCmd;
    private final String user;      // nullable for DBs without auth (Mongo, Redis)
    private final String password;  // nullable
    private final String dbName;    // nullable (Redis has no DB name)
    private final String dataDir;   // path to mount as tmpfs for ephemeral data

    DatabaseType(String image, int port, String healthcheckCmd,
                 String user, String password, String dbName, String dataDir) {
        this.image = image;
        this.port = port;
        this.healthcheckCmd = healthcheckCmd;
        this.user = user;
        this.password = password;
        this.dbName = dbName;
        this.dataDir = dataDir;
    }
}
