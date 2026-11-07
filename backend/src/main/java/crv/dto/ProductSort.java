package crv.dto;

/** Orders for a product list. Search relevance comes later, with the assistant. */
public enum ProductSort {
    /** Highest popularity first: rating times a dampened review count. */
    POPULARITY,
    /** Cheapest matching variant first. */
    PRICE_ASC,
    /** Most expensive matching variant first. */
    PRICE_DESC
}
