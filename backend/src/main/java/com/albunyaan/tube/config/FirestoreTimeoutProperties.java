package com.albunyaan.tube.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Firestore operation timeouts.
 *
 * Allows operation-specific timeout configuration to prevent indefinite blocking
 * and thread pool exhaustion in case of network issues or Firestore unavailability.
 *
 * Timeouts are specified in seconds with sensible defaults for different operation types:
 * - READ_TIMEOUT: Single document reads (default: 5s)
 * - WRITE_TIMEOUT: Document writes/updates (default: 10s)
 * - BULK_QUERY_TIMEOUT: Collection queries, findAll, search operations (default: 30s)
 */
@Configuration
@ConfigurationProperties(prefix = "app.firebase.firestore.timeout")
@Validated
public class FirestoreTimeoutProperties {

    /**
     * Timeout for single document read operations (in seconds)
     * Must be at least 1 second
     */
    @Min(value = 1, message = "Read timeout must be at least 1 second")
    private long read = 5;

    /**
     * Timeout for document write/update operations (in seconds)
     * Must be at least 1 second
     */
    @Min(value = 1, message = "Write timeout must be at least 1 second")
    private long write = 10;

    /**
     * Timeout for bulk query operations like findAll, search (in seconds)
     * Must be at least 5 seconds for complex queries
     */
    @Min(value = 5, message = "Bulk query timeout must be at least 5 seconds")
    private long bulkQuery = 30;

    /**
     * Default maximum number of results for unbounded queries
     * Must be between 1 and 100,000 results
     */
    @Min(value = 1, message = "Default max results must be at least 1")
    @Max(value = 100000, message = "Default max results must not exceed 100,000")
    private int defaultMaxResults = 1000;

    public long getRead() {
        return read;
    }

    public void setRead(long read) {
        this.read = read;
    }

    public long getWrite() {
        return write;
    }

    public void setWrite(long write) {
        this.write = write;
    }

    public long getBulkQuery() {
        return bulkQuery;
    }

    public void setBulkQuery(long bulkQuery) {
        this.bulkQuery = bulkQuery;
    }

    public int getDefaultMaxResults() {
        return defaultMaxResults;
    }

    public void setDefaultMaxResults(int defaultMaxResults) {
        this.defaultMaxResults = defaultMaxResults;
    }
}
