package crv.entity;

/** The result of the statistical list price screen (docs/data-and-search.md, "List price checks"). */
public enum ListPriceStatus {
    /** Typical for its category, or above it: high outliers are mostly real luxury items. */
    TRUSTED,
    /** Far below its category's typical price, such as a $0.01 bra. Stored, but never shown or filtered on. */
    SUSPECT
}
