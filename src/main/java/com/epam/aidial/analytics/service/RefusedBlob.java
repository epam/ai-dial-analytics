package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.blob.BlobListing;

/**
 * A blob whose rows ClickHouse still refused with a data-class error once its batch was split by blob (see the
 * {@code data-sync} spec, "A batch ClickHouse refuses for its data is split by blob"). Its rows are not written and it
 * is not recorded in the ingest ledger; the ingest path records it in the failure ledger as {@code rejected_by_server}.
 *
 * @param listing the refused blob
 * @param table   the target table whose insert refused it
 * @param code    the ClickHouse server error code
 * @param name    the server error code's name
 */
public record RefusedBlob(BlobListing listing, String table, int code, String name) {

    /** The failure-ledger message: the table and the server error, never row content. */
    public String describe() {
        return "ClickHouse refused the blob's rows for " + table + ": " + code + " " + name;
    }
}
