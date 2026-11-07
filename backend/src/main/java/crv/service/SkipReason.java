package crv.service;

/** Why the ingest left a record out. */
public enum SkipReason {
    NO_ID,
    DUPLICATE_ID,
    NO_TITLE,
    NO_PRICE
}
